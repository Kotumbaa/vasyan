package `fun`.tryliner.android.core.network

import `fun`.tryliner.android.core.model.SearchResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InnertubeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: InnertubeClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = InnertubeClient(baseUrlOverride = server.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search parses tracks from WEB_REMIX response`() = runTest {
        val searchJson = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicShelfRenderer": {
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "playlistItemData": { "videoId": "dQw4w9WgXcQ" },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "runs": [{ "text": "Never Gonna Give You Up" }] }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Rick Astley",
                                                    "navigationEndpoint": {
                                                      "browseEndpoint": {
                                                        "browseId": "UC123",
                                                        "browseEndpointContextSupportedConfigs": {
                                                          "browseEndpointContextMusicConfig": {
                                                            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                                          }
                                                        }
                                                      }
                                                    }
                                                  },
                                                  { "text": " • " },
                                                  { "text": "3:33" }
                                                ]
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(searchJson))

        val results = client.search("never gonna give you up", type = "track")
        assertEquals(1, results.size)
        val track = results.first() as SearchResult.TrackItem
        assertEquals("dQw4w9WgXcQ", track.id)
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artists.first().name)
        assertEquals(213_000L, track.durationMs)
    }

    @Test
    fun `resolveAudioStream selects preferred opus format and probes reachability`() = runTest {
        val streamUrl = server.url("/videoplayback?expire=1893456000").toString()
        val playerJson = """
            {
              "playabilityStatus": { "status": "OK" },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 140,
                    "url": "$streamUrl",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000
                  },
                  {
                    "itag": 251,
                    "url": "$streamUrl",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 160000
                  }
                ]
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(playerJson))
        server.enqueue(MockResponse().setResponseCode(200))

        val resolved = client.resolveAudioStream("yt:dQw4w9WgXcQ", preferredCodec = "opus", verifyReachable = true)
        assertEquals(251, resolved.itag)
        assertEquals("opus", resolved.codec)
        assertEquals("audio/webm", resolved.mimeType)
        assertEquals(160_000, resolved.bitrate)
        assertTrue(resolved.url.contains("/videoplayback"))

        val playerReq = server.takeRequest()
        assertEquals("/youtubei/v1/player?prettyPrint=false", playerReq.path)
        val probeReq = server.takeRequest()
        assertTrue(probeReq.path!!.contains("range=0-0"))
    }
}
