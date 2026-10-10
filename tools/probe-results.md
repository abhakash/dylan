
## 2026-08-21T19:50:35.212835Z mode=ci
| S1 | CI | FAIL | live search mapped zero songs |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T19:52:04.222676Z mode=ci
| S1 | CI | FAIL | raw 73677 chars ok but mapped zero; sample='{"total":5211,"start":1,"results":[{"id":"YiVML4Zo","title":"Gehra Hua (From &quot;Dhurandhar&quot;)","subtitle":"Shashwat Sachdev, Arijit Singh, Irshad Kamil, Armaan Khan - Gehra Hua (From &quot;Dhur' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T19:52:58.756156Z mode=ci
| S1 | CI | FAIL | mapped zero: decode=ok directMapped=20 |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T19:54:00.473619Z mode=ci
| S1 | CI | FAIL | provider zero; direct=20 decode=ok / status=200 body<String>=len=79 head='{"error":{"code":"INPUT_MISSIN' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T19:55:01.365910Z mode=ci
| S1 | CI | FAIL | provider zero; direct=20 / replica status=200 len=73677 head='{"total":5211,"start":1,"results":[{"id":"YiVML4Zo","title":' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T19:55:42.016799Z mode=ci
| S1 | CI | FAIL | provider zero; direct=20 / replica status=200 len=73677 head='{"total":5211,"start":1,"results":[{"id":"YiVML4Zo","title":' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T19:56:03.910158Z mode=ci
| S1 | CI | FAIL | provider zero; direct=20 / replica status=200 len=73677 head='{"total":5211,"start":1,"results":[{"id":"YiVML4Zo","title":' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T20:02:41.032658Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T20:03:05.163635Z mode=local
| P5 | M0 | PASS | catalog cacheability over 60 sampled songs :: date=2026-08-22 nonCacheable=0/60 (0.0%) |
| P1 | M0 | FAIL | GET Range expected 206 got 403 |
| P2 | M0 | FAIL | no ETag on media response - If-Range resume guard unusable |
| P3 | M0 | FAIL | full GET got 403 |
| P13 | M0 | FAIL | media GET got 403 |
| P11 | M0 | PASS | signed-URL TTL >= 60s (server-windowed) :: SKIPPED (--fast) |
| P12 | M0 | PASS | WS handshake + round-trip < 2s :: handshake+answer 426ms |
| P4 | M0 | PASS | WS correlation decision (3 rapid queries, one socket) :: correlation=ORDERED (identity echoed in 0/3 frames) -> set SearchChannel.correlationMode accordingly |
| P8 | M0 | PASS | autocomplete.get as plain HTTP GET :: JSON payload ok (7320 chars) |
| P10 | M0 | PASS | -500x500 image variant exists (3 samples) :: 3/3 variants served |
| P6 | M1 | PASS | re-sign path stability (two generateAuthToken calls) :: samePath=true path=https://web.saavncdn.com/114/9109db7f112172c6c6246e929a818c1d_160.mp4 |
| P7 | M1 | PASS | bitrate calibration CL/duration (replaces x125) :: 128kbps=1B/s(x125 would be 16000) / 320kbps=1B/s(x125 would be 40000) |
| P9 | M1 | PASS | geo sanity: CDN edge logged :: apiHost=www.jiosaavn.com cdnEdge=web.saavncdn.com signedType=mp4 |

## 2026-08-21T20:04:24.966764Z mode=local
| P5 | M0 | PASS | catalog cacheability over 59 sampled songs :: date=2026-08-22 nonCacheable=0/59 (0.0%) |
| P1 | M0 | FAIL | If-Range bogus etag gave 206 expected full 200 |
| P2 | M0 | PASS | ETag present on media response :: ETag="0x8DEF86AF04A4820"... |
| P3 | M0 | FAIL | truthful-length violated: header=3723827 actual=429 |
| P13 | M0 | FAIL | DRIFT: body starts with '<' (HTML) |
| P11 | M0 | PASS | signed-URL TTL >= 60s (server-windowed) :: SKIPPED (--fast) |
| P12 | M0 | PASS | WS handshake + round-trip < 2s :: handshake+answer 390ms |
| P4 | M0 | PASS | WS correlation decision (3 rapid queries, one socket) :: correlation=ORDERED (identity echoed in 0/3 frames) -> set SearchChannel.correlationMode accordingly |
| P8 | M0 | PASS | autocomplete.get as plain HTTP GET :: JSON payload ok (7320 chars) |
| P10 | M0 | PASS | -500x500 image variant exists (3 samples) :: 3/3 variants served |
| P6 | M1 | PASS | re-sign path stability (two generateAuthToken calls) :: samePath=true path=https://web.saavncdn.com/114/9109db7f112172c6c6246e929a818c1d_160.mp4 |
| P7 | M1 | PASS | bitrate calibration CL/duration (replaces x125) :: 128kbps=20377B/s(x125 would be 16000) / 320kbps=40408B/s(x125 would be 40000) |
| P9 | M1 | PASS | geo sanity: CDN edge logged :: apiHost=www.jiosaavn.com cdnEdge=web.saavncdn.com signedType=mp4 |

## 2026-08-21T20:04:35.729003Z mode=local
| P5 | M0 | PASS | catalog cacheability over 59 sampled songs :: date=2026-08-22 nonCacheable=0/59 (0.0%) |
| P1 | M0 | PASS | Range + If-Range semantics on web.saavncdn.com :: AcceptRanges=bytes 206CL=100 bogus-etag=>200(expect 200) matching=>206(expect 206) |
| P2 | M0 | PASS | ETag present on media response :: ETag="0x8DEF86AF04A4820"... |
| P3 | M0 | FAIL | truthful-length violated: header=3723827 actual=429 |
| P13 | M0 | FAIL | DRIFT: body starts with '<' (HTML) |
| P11 | M0 | PASS | signed-URL TTL >= 60s (server-windowed) :: SKIPPED (--fast) |
| P12 | M0 | PASS | WS handshake + round-trip < 2s :: handshake+answer 364ms |
| P4 | M0 | PASS | WS correlation decision (3 rapid queries, one socket) :: correlation=ORDERED (identity echoed in 0/3 frames) -> set SearchChannel.correlationMode accordingly |
| P8 | M0 | PASS | autocomplete.get as plain HTTP GET :: JSON payload ok (7320 chars) |
| P10 | M0 | PASS | -500x500 image variant exists (3 samples) :: 3/3 variants served |
| P6 | M1 | PASS | re-sign path stability (two generateAuthToken calls) :: samePath=true path=https://web.saavncdn.com/114/9109db7f112172c6c6246e929a818c1d_160.mp4 |
| P7 | M1 | PASS | bitrate calibration CL/duration (replaces x125) :: 128kbps=20377B/s(x125 would be 16000) / 320kbps=40408B/s(x125 would be 40000) |
| P9 | M1 | PASS | geo sanity: CDN edge logged :: apiHost=www.jiosaavn.com cdnEdge=web.saavncdn.com signedType=mp4 |

## 2026-08-21T20:08:48.689104Z mode=local
| P5 | M0 | PASS | catalog cacheability over 60 sampled songs :: date=2026-08-22 nonCacheable=0/60 (0.0%) |
| P1 | M0 | PASS | Range + If-Range semantics on web.saavncdn.com :: AcceptRanges=bytes 206CL=100 bogus-etag=>200(expect 200) matching=>206(expect 206) |
| P2 | M0 | PASS | ETag present on media response :: ETag="0x8DEF86AF04A4820"... |
| P3 | M0 | PASS | Content-Length present and truthful :: CL truthful: 3723827 bytes for 177s track |
| P13 | M0 | PASS | signed-media DRIFT sentinel (audio/* not HTML) :: CT=audio/mp4 |
| P11 | M0 | PASS | signed-URL TTL >= 60s (server-windowed) :: SKIPPED (--fast) |
| P12 | M0 | PASS | WS handshake + round-trip < 2s :: handshake+answer 421ms |
| P4 | M0 | PASS | WS correlation decision (3 rapid queries, one socket) :: correlation=ORDERED (identity echoed in 0/3 frames) -> set SearchChannel.correlationMode accordingly |
| P8 | M0 | PASS | autocomplete.get as plain HTTP GET :: JSON payload ok (7320 chars) |
| P10 | M0 | PASS | -500x500 image variant exists (3 samples) :: 3/3 variants served |
| P6 | M1 | PASS | re-sign path stability (two generateAuthToken calls) :: samePath=true path=https://web.saavncdn.com/114/9109db7f112172c6c6246e929a818c1d_160.mp4 |
| P7 | M1 | PASS | bitrate calibration CL/duration (replaces x125) :: 128kbps=20377B/s(x125 would be 16000) / 320kbps=40408B/s(x125 would be 40000) |
| P9 | M1 | PASS | geo sanity: CDN edge logged :: apiHost=www.jiosaavn.com cdnEdge=web.saavncdn.com signedType=mp4 |

## 2026-08-21T20:10:30.997078Z mode=local
| P5 | M0 | PASS | catalog cacheability over 59 sampled songs :: date=2026-08-22 nonCacheable=0/59 (0.0%) |
| P1 | M0 | PASS | Range + If-Range semantics on web.saavncdn.com :: AcceptRanges=bytes 206CL=100 bogus-etag=>200(expect 200) matching=>206(expect 206) |
| P2 | M0 | PASS | ETag present on media response :: ETag="0x8DEF86AF04A4820"... |
| P3 | M0 | PASS | Content-Length present and truthful :: CL truthful: 3723827 bytes for 177s track |
| P13 | M0 | PASS | signed-media DRIFT sentinel (audio/* not HTML) :: CT=audio/mp4 |
| P11 | M0 | TIMEOUT | signed-URL TTL >= 60s (server-windowed) (exceeded 45s) |
| P12 | M0 | PASS | WS handshake + round-trip < 2s :: handshake+answer 401ms |
| P4 | M0 | PASS | WS correlation decision (3 rapid queries, one socket) :: correlation=ORDERED (identity echoed in 0/3 frames) -> set SearchChannel.correlationMode accordingly |
| P8 | M0 | PASS | autocomplete.get as plain HTTP GET :: JSON payload ok (7320 chars) |
| P10 | M0 | PASS | -500x500 image variant exists (3 samples) :: 3/3 variants served |
| P6 | M1 | PASS | re-sign path stability (two generateAuthToken calls) :: samePath=true path=https://web.saavncdn.com/114/9109db7f112172c6c6246e929a818c1d_160.mp4 |
| P7 | M1 | PASS | bitrate calibration CL/duration (replaces x125) :: 128kbps=20377B/s(x125 would be 16000) / 320kbps=40408B/s(x125 would be 40000) |
| P9 | M1 | PASS | geo sanity: CDN edge logged :: apiHost=www.jiosaavn.com cdnEdge=web.saavncdn.com signedType=mp4 |

## 2026-08-21T20:21:00.862199Z mode=local
| P5 | M0 | PASS | catalog cacheability over 59 sampled songs :: date=2026-08-22 nonCacheable=0/59 (0.0%) |
| P1 | M0 | PASS | Range + If-Range semantics on web.saavncdn.com :: AcceptRanges=bytes 206CL=100 bogus-etag=>200(expect 200) matching=>206(expect 206) |
| P2 | M0 | PASS | ETag present on media response :: ETag="0x8DEF86AF04A4820"... |
| P3 | M0 | PASS | Content-Length present and truthful :: CL truthful: 3723827 bytes for 177s track |
| P13 | M0 | PASS | signed-media DRIFT sentinel (audio/* not HTML) :: CT=audio/mp4 |
| P11 | M0 | PASS | signed-URL TTL >= 60s (server-windowed) :: still valid past 10min (dominates [60s,10min] assumption) |
| P12 | M0 | PASS | WS handshake + round-trip < 2s :: handshake+answer 567ms |
| P4 | M0 | PASS | WS correlation decision (3 rapid queries, one socket) :: correlation=ORDERED (identity echoed in 0/3 frames) -> set SearchChannel.correlationMode accordingly |
| P8 | M0 | PASS | autocomplete.get as plain HTTP GET :: JSON payload ok (7320 chars) |
| P10 | M0 | PASS | -500x500 image variant exists (3 samples) :: 3/3 variants served |
| P6 | M1 | PASS | re-sign path stability (two generateAuthToken calls) :: samePath=true path=https://web.saavncdn.com/114/9109db7f112172c6c6246e929a818c1d_160.mp4 |
| P7 | M1 | PASS | bitrate calibration CL/duration (replaces x125) :: 128kbps=20377B/s(x125 would be 16000) / 320kbps=40408B/s(x125 would be 40000) |
| P9 | M1 | PASS | geo sanity: CDN edge logged :: apiHost=www.jiosaavn.com cdnEdge=web.saavncdn.com signedType=mp4 |

## 2026-08-21T21:05:11.468050Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T21:33:01.598586Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-21T21:35:02.319666Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T05:30:49.261688Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T06:37:43.634981Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T06:38:17.322205Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T08:03:32.622705Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T10:45:51.092586Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T10:58:35.489028Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T14:29:27.260686Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T14:30:42.834476Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T14:30:51.604992Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T17:18:32.166727Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T17:19:03.389746Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-22T17:19:42.797166Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |

## 2026-08-23T06:01:13.702759Z mode=ci
| S1 | CI | PASS | api.php returns JSON (search.getResults live shape) :: mapped 20 songs, first='Gehra Hua (From &quot;Dhurandhar&quot;)' |
| S2 | CI | PASS | autocomplete reachable over plain HTTP :: ok |
| S3 | CI | PASS | WS handshake reachable :: reachable |
