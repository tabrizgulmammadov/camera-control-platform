# Graph Report - C:\Users\AA3810475\Desktop\Working Area\VMS\Projects\onvif-testing  (2026-07-20)

## Corpus Check
- Corpus is ~24,613 words - fits in a single context window. You may not need a graph.

## Summary
- 372 nodes · 803 edges · 15 communities (13 shown, 2 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 102 edges (avg confidence: 0.8)
- Token cost: 50,479 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Stream Manager & ffmpeg|Stream Manager & ffmpeg]]
- [[_COMMUNITY_Camera Driver Abstraction|Camera Driver Abstraction]]
- [[_COMMUNITY_Error Handling & Codes|Error Handling & Codes]]
- [[_COMMUNITY_Profile & Encoder Models|Profile & Encoder Models]]
- [[_COMMUNITY_Architecture & API Docs|Architecture & API Docs]]
- [[_COMMUNITY_Frontend App & API Client|Frontend App & API Client]]
- [[_COMMUNITY_ONVIF Parser Tests|ONVIF Parser Tests]]
- [[_COMMUNITY_ONVIF Driver Integration Tests|ONVIF Driver Integration Tests]]
- [[_COMMUNITY_Stream REST API|Stream REST API]]
- [[_COMMUNITY_Web Config & HLS Storage|Web Config & HLS Storage]]
- [[_COMMUNITY_WS-Security Digest|WS-Security Digest]]
- [[_COMMUNITY_Frontend Package Manifest|Frontend Package Manifest]]
- [[_COMMUNITY_Maven Wrapper|Maven Wrapper]]
- [[_COMMUNITY_Spring Boot Entrypoint|Spring Boot Entrypoint]]
- [[_COMMUNITY_Backend Package Root|Backend Package Root]]

## God Nodes (most connected - your core abstractions)
1. `CameraException` - 28 edges
2. `StreamManager` - 26 edges
3. `StreamSession` - 17 edges
4. `StreamManagerTest` - 17 edges
5. `OnvifDriverIntegrationTest` - 16 edges
6. `MediaProfile` - 14 edges
7. `OnvifParsers` - 14 edges
8. `ApiExceptionHandlerTest` - 14 edges
9. `OnvifParsersTest` - 13 edges
10. `CameraConnection` - 11 edges

## Surprising Connections (you probably didn't know these)
- `Idle Stream Auto-Reaping (>5 min)` --semantically_similar_to--> `DELETE /api/stream/{streamId}`  [INFERRED] [semantically similar]
  backend/README.md → API-CONTRACT.md
- `Playlist Readiness Retry (~15 s)` --semantically_similar_to--> `Frontend Contract Client (src/api.js)`  [INFERRED] [semantically similar]
  API-CONTRACT.md → ARCHITECTURE.md
- `Camera Check Backend (Spring Boot)` --implements--> `Shared API Contract (Frontend-Backend)`  [EXTRACTED]
  backend/README.md → API-CONTRACT.md
- `API Layer (HTTP Boundary)` --implements--> `Error Envelope`  [EXTRACTED]
  ARCHITECTURE.md → API-CONTRACT.md
- `ONVIF Mode` --references--> `POST /api/onvif/profiles`  [INFERRED]
  frontend/README.md → API-CONTRACT.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **RTSP to HLS Playback Flow** — api_contract_stream_start_endpoint, architecture_stream_manager, backend_readme_ffmpeg, frontend_readme_hls_js, architecture_frontend_api_client [EXTRACTED 1.00]
- **ONVIF SOAP Session Stack** — architecture_soap_client, architecture_ws_security_header, architecture_onvif_connection, architecture_driver_onvif_layer [EXTRACTED 1.00]
- **Layered Backend Architecture (api/domain/driver/streaming)** — architecture_api_layer, architecture_domain_layer, architecture_driver_onvif_layer, architecture_streaming_layer [EXTRACTED 1.00]

## Communities (15 total, 2 thin omitted)

### Community 0 - "Stream Manager & ffmpeg"
Cohesion: 0.09
Nodes (11): Logger, SecureRandom, StreamManager, StreamSession, AfterEach, BeforeEach, Test, StreamManagerTest (+3 more)

### Community 1 - "Camera Driver Abstraction"
Cohesion: 0.08
Nodes (17): OnvifProfilesRequest, PostMapping, RequestMapping, RestController, OnvifController, CameraConnection, Override, CameraDriver (+9 more)

### Community 2 - "Error Handling & Codes"
Cohesion: 0.11
Nodes (18): ApiExceptionHandler, Logger, ErrorResponse, CameraException, ErrorCode, Document, SoapClient, ApiExceptionHandlerTest (+10 more)

### Community 3 - "Profile & Encoder Models"
Cohesion: 0.11
Nodes (16): AudioEncoderDto, DeviceInfoDto, OnvifProfilesResponse, ProfileDto, ResolutionDto, VideoEncoderDto, AudioEncoderConfig, DeviceInformation (+8 more)

### Community 4 - "Architecture & API Docs"
Cohesion: 0.07
Nodes (37): Error Envelope, POST /api/onvif/profiles, Playlist Readiness Retry (~15 s), Shared API Contract (Frontend-Backend), GET /api/stream/{streamId} (health), POST /api/stream/start, DELETE /api/stream/{streamId}, StreamType MAIN/SUB Heuristic (+29 more)

### Community 5 - "Frontend App & API Client"
Cohesion: 0.15
Nodes (25): ApiError, BackendDownError, getOnvifProfiles(), getStreamDetails(), request(), startStream(), stopStream(), waitForPlaylist() (+17 more)

### Community 6 - "ONVIF Parser Tests"
Cohesion: 0.18
Nodes (4): Document, Test, OnvifParsersTest, OnvifSoapFixtures

### Community 7 - "ONVIF Driver Integration Tests"
Cohesion: 0.23
Nodes (6): AfterEach, BeforeEach, Test, OnvifDriverIntegrationTest, HttpExchange, HttpServer

### Community 8 - "Stream REST API"
Cohesion: 0.16
Nodes (12): StreamDetailsResponse, StreamStartRequest, Details, StreamStartResponse, PostMapping, RequestMapping, RestController, StreamController (+4 more)

### Community 9 - "Web Config & HLS Storage"
Cohesion: 0.18
Nodes (9): Override, WebConfig, HlsStorage, Component, Logger, Configuration, CorsRegistry, ResourceHandlerRegistry (+1 more)

### Community 10 - "WS-Security Digest"
Cohesion: 0.24
Nodes (4): SecureRandom, WsSecurityHeader, Test, WsSecurityHeaderTest

### Community 11 - "Frontend Package Manifest"
Cohesion: 0.15
Nodes (12): dependencies, hls.js, devDependencies, vite, name, private, scripts, build (+4 more)

### Community 13 - "Spring Boot Entrypoint"
Cohesion: 0.60
Nodes (3): CameraCheckApplication, EnableScheduling, SpringBootApplication

## Knowledge Gaps
- **18 isolated node(s):** `com.cameracheck:camera-check-backend`, `name`, `private`, `version`, `type` (+13 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `CameraException` connect `Error Handling & Codes` to `Stream Manager & ffmpeg`, `Camera Driver Abstraction`, `Profile & Encoder Models`, `ONVIF Parser Tests`, `ONVIF Driver Integration Tests`, `Stream REST API`?**
  _High betweenness centrality (0.217) - this node is a cross-community bridge._
- **Why does `StreamManager` connect `Stream Manager & ffmpeg` to `Stream REST API`, `Camera Driver Abstraction`, `Error Handling & Codes`, `Web Config & HLS Storage`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **What connects `com.cameracheck:camera-check-backend`, `name`, `private` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Stream Manager & ffmpeg` be split into smaller, more focused modules?**
  _Cohesion score 0.08563134978229318 - nodes in this community are weakly interconnected._
- **Should `Camera Driver Abstraction` be split into smaller, more focused modules?**
  _Cohesion score 0.08139534883720931 - nodes in this community are weakly interconnected._
- **Should `Error Handling & Codes` be split into smaller, more focused modules?**
  _Cohesion score 0.10631229235880399 - nodes in this community are weakly interconnected._
- **Should `Profile & Encoder Models` be split into smaller, more focused modules?**
  _Cohesion score 0.11149825783972125 - nodes in this community are weakly interconnected._