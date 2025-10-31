package coil3.network

import coil3.Extras
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.test.utils.RobolectricTest
import coil3.test.utils.context
import coil3.test.utils.runTestAsync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.fakefilesystem.FakeFileSystem

class NetworkFetcherTest : RobolectricTest() {

    @Test
    fun networkRequestParamsArePassedThrough() = runTestAsync {
        val expectedSize = 1_000
        val url = "https://example.com/image.jpg"
        val method = "POST"
        val headers = NetworkHeaders.Builder()
            .set("key", "value")
            .build()
        val body = NetworkRequestBody(ByteArray(500).toByteString())
        val options = Options(
            context = context,
            extras = Extras.Builder()
                .set(Extras.Key.httpMethod, method)
                .set(Extras.Key.httpHeaders, headers)
                .set(Extras.Key.httpBody, body)
                .build(),
        )
        val networkClient = FakeNetworkClient(
            respond = {
                NetworkResponse(
                    body = NetworkResponseBody(
                        source = Buffer().apply { write(ByteArray(expectedSize)) },
                    ),
                )
            },
        )
        val result = NetworkFetcher(
            url = url,
            options = options,
            networkClient = lazyOf(networkClient),
            diskCache = lazyOf(null),
            cacheStrategy = lazyOf(CacheStrategy.DEFAULT),
            connectivityChecker = ConnectivityChecker(context),
        ).fetch()

        assertIs<SourceFetchResult>(result)

        val expected = NetworkRequest(url, method, headers, body, options.extras)

        assertEquals(expected, networkClient.requests.single())
    }

    @Test
    fun error404ResponseIsCachedToDisk() = runTestAsync {
        val expectedSize = 1_000
        val url = "https://example.com/error.jpg"

        val fileSystem = FakeFileSystem()
        val diskCache = DiskCache.Builder()
            .directory(fileSystem.workingDirectory)
            .fileSystem(fileSystem)
            .maxSizeBytes(Long.MAX_VALUE)
            .build()

        val networkClient = FakeNetworkClient(
            respond = {
                NetworkResponse(
                    code = 404,
                    body = NetworkResponseBody(
                        source = Buffer().apply { write(ByteArray(expectedSize)) },
                    ),
                )
            },
        )

        val fetcher = NetworkFetcher(
            url = url,
            options = Options(context),
            networkClient = lazyOf(networkClient),
            diskCache = lazyOf(diskCache),
            cacheStrategy = lazyOf(CacheStrategy.DEFAULT),
            connectivityChecker = ConnectivityChecker.ONLINE,
        )

        // A 400 response throws, but should still be cached.
        assertFailsWith<HttpException> { fetcher.fetch() }

        diskCache.openSnapshot(url)!!.use { snapshot ->
            // Verify the cached metadata records the 400 status code.
            val cachedResponse = diskCache.fileSystem.read(snapshot.metadata) {
                CacheNetworkResponse.readFrom(this)
            }
            assertEquals(404, cachedResponse.code)

            // Verify the cached data exists and has the expected size.
            val actualSize = fileSystem.read(snapshot.data) { readByteString().size }
            assertEquals(expectedSize, actualSize)
        }

        diskCache.shutdown()
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun error500ResponseIsNotCachedToDisk() = runTestAsync {
        val expectedSize = 1_000
        val url = "https://example.com/error-500.jpg"

        val fileSystem = FakeFileSystem()
        val diskCache = DiskCache.Builder()
            .directory(fileSystem.workingDirectory)
            .fileSystem(fileSystem)
            .maxSizeBytes(Long.MAX_VALUE)
            .build()

        val networkClient = FakeNetworkClient(
            respond = {
                NetworkResponse(
                    code = 500,
                    body = NetworkResponseBody(
                        source = Buffer().apply { write(ByteArray(expectedSize)) },
                    ),
                )
            },
        )

        val fetcher = NetworkFetcher(
            url = url,
            options = Options(context),
            networkClient = lazyOf(networkClient),
            diskCache = lazyOf(diskCache),
            cacheStrategy = lazyOf(CacheStrategy.DEFAULT),
            connectivityChecker = ConnectivityChecker.ONLINE,
        )

        // A 500 response throws and should not be cached.
        assertFailsWith<HttpException> { fetcher.fetch() }

        // Verify no snapshot was written for the 500 response.
        assertEquals(null, diskCache.openSnapshot(url))

        diskCache.shutdown()
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun cached404ResponseThrows() = runTestAsync {
        val url = "https://example.com/cached-404.jpg"

        val fileSystem = FakeFileSystem()
        val diskCache = DiskCache.Builder()
            .directory(fileSystem.workingDirectory)
            .fileSystem(fileSystem)
            .maxSizeBytes(Long.MAX_VALUE)
            .build()

        // Pre-populate the disk cache with a cached 404 response.
        val editor = diskCache.openEditor(url)!!
        fileSystem.write(editor.metadata) {
            CacheNetworkResponse.writeTo(NetworkResponse(code = 404), this)
        }

        // Write some data as well to ensure it's a complete entry.
        fileSystem.write(editor.data) {
            write(ByteArray(32))
        }
        editor.commit()

        val networkClient = FakeNetworkClient(
            respond = {
                // Should not be invoked since we throw on cached 404 before network.
                NetworkResponse(
                    body = NetworkResponseBody(Buffer().apply { write(ByteArray(1)) }),
                )
            },
        )

        val fetcher = NetworkFetcher(
            url = url,
            options = Options(context),
            networkClient = lazyOf(networkClient),
            diskCache = lazyOf(diskCache),
            cacheStrategy = lazyOf(CacheStrategy.DEFAULT),
            connectivityChecker = ConnectivityChecker.ONLINE,
        )

        assertFailsWith<HttpException> { fetcher.fetch() }
        assertEquals(0, networkClient.requests.size)

        diskCache.shutdown()
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun concurrentRequestsAreMerged() = runTestAsync {
        val url = "https://example.com/image.jpg"
        val options = Options(context)
        val networkClient = FakeNetworkClient {
            // Add a small delay to ensure the concurrent requests have time to queue up.
            kotlinx.coroutines.delay(100)
            NetworkResponse(
                body = NetworkResponseBody(
                    source = Buffer().apply { write(ByteArray(1000)) },
                ),
            )
        }
        val fetcher = NetworkFetcher(
            url = url,
            options = options,
            networkClient = lazyOf(networkClient),
            diskCache = lazyOf(null),
            cacheStrategy = lazyOf(CacheStrategy.DEFAULT),
            connectivityChecker = ConnectivityChecker(context),
        )

        // Launch 10 concurrent fetch requests.
        val jobs = List(10) {
            async { fetcher.fetch() }
        }
        val results = jobs.awaitAll()

        // All results should be the same instance.
        val firstResult = results.first()
        results.forEach {
            assertIs<SourceFetchResult>(it)
            assertTrue(firstResult === it)
        }

        // The network client should have only received one request.
        assertEquals(1, networkClient.requests.size)
    }

    @Test
    fun concurrentRequestsAreMergedWhenRequestFails() = runTestAsync {
        val url = "https://example.com/image.jpg"
        val options = Options(context)
        val exception = RuntimeException("test")
        val networkClient = FakeNetworkClient {
            // Add a small delay to ensure the concurrent requests have time to queue up.
            kotlinx.coroutines.delay(100)
            throw exception
        }
        val fetcher = NetworkFetcher(
            url = url,
            options = options,
            networkClient = lazyOf(networkClient),
            diskCache = lazyOf(null),
            cacheStrategy = lazyOf(CacheStrategy.DEFAULT),
            connectivityChecker = ConnectivityChecker(context),
        )

        // Launch 10 concurrent fetch requests.
        val jobs = List(10) {
            async {
                try {
                    assertFailsWith<RuntimeException> {
                        fetcher.fetch()
                    }
                } catch (ex: Exception) {
                    assertTrue(ex === exception)
                }
            }
        }
        jobs.awaitAll()

        // The network client should have only received one request.
        assertEquals(1, networkClient.requests.size)
    }

    @Test
    fun concurrentRequestsCompleteWhenSomeAreCancelled() = runTestAsync {
        val url = "https://example.com/image.jpg"
        val options = Options(context)
//        val networkRequestStarted = CompletableDeferred<Unit>()
        val networkClient = FakeNetworkClient {
//            networkRequestStarted.complete(Unit)
            // Add a delay to ensure cancellation occurs before completion.
            kotlinx.coroutines.delay(200)
            NetworkResponse(
                body = NetworkResponseBody(
                    source = Buffer().apply { write(ByteArray(1000)) },
                ),
            )
        }
        val fetcher = NetworkFetcher(
            url = url,
            options = options,
            networkClient = lazyOf(networkClient),
            diskCache = lazyOf(null),
            cacheStrategy = lazyOf(CacheStrategy.DEFAULT),
            connectivityChecker = ConnectivityChecker(context),
        )

        // Launch the worker job and wait for it to begin the network request.
        // This ensures the worker is not among the jobs that are cancelled.
        val workerJob = async { fetcher.fetch() }
//        networkRequestStarted.await()

        // Launch subsequent jobs that will wait for the worker.
        val waitingJobs = List(10) {
            async { fetcher.fetch() }
        }

        // Cancel half of the waiting jobs.
        val jobsToCancel = waitingJobs.take(5)
        val remainingJobs = waitingJobs.drop(5)
        jobsToCancel.forEach { it.cancel() }

        // Verify that the cancelled jobs failed with CancellationException.
        for (job in jobsToCancel) {
            assertFailsWith<CancellationException> {
                job.await()
            }
        }

        // Verify that the remaining jobs and the original worker job completed successfully.
        val results = (remainingJobs + workerJob).awaitAll()
        val firstResult = results.first()
        results.forEach {
            assertIs<SourceFetchResult>(it)
            assertTrue(firstResult === it)
        }

        // The network client should have still only received one request.
        assertEquals(1, networkClient.requests.size)
    }

    class FakeNetworkClient(
        private val respond: suspend (NetworkRequest) -> NetworkResponse,
    ) : NetworkClient {
        val requests = mutableListOf<NetworkRequest>()
        val responses = mutableListOf<NetworkResponse>()

        override suspend fun <T> executeRequest(
            request: NetworkRequest,
            block: suspend (response: NetworkResponse) -> T,
        ): T {
            requests += request
            val response = respond(request)
            responses += response
            return block(response)
        }
    }
}
