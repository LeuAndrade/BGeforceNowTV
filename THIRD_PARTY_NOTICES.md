# Referências, adaptações e dependências

Este é um projeto Android independente, com nome, pacote, navegação e interface próprios. Não é um fork nem requer duplicar um repositório. Há adaptações de protocolo provenientes de software MIT; criar um repositório novo não elimina a obrigação de atribuição.

## OpenNOW

- Repositório: https://github.com/OpenCloudGaming/OpenNOW
- Revisão consultada para o protocolo Android: `1bea55a39c23d66780b9aedd85a5d967173d58d6`.
- Autoria upstream: Copyright (c) 2025 Zortos e contribuidores.
- Licença da revisão consultada: MIT, preservada em `licenses/OpenNOW-MIT.txt`.
- Referências: `GfnApi.kt`, `GfnLibraryCatalog.kt`, `GfnSessionConnections.kt`, `StreamSdp.kt`, `StreamSignaling.kt`, `StreamInputEncoder.kt`, `StreamNetworkAdaptation.kt`, `Models.kt`, `MediaCodecSurfaceVideoDecoder.kt`, `SurfaceOrTextureVideoDecoder.kt`, `StreamSurfaceDecoderPolicy.kt` e `LowLatencyVideoDecoder.kt`. Na 0.12, também foram consultados a separação dos contadores de reconexão em `Streaming.kt` e os testes/formato em `StreamSignaling.kt`; a implementação Java e os testes de integração são próprios.
- Adaptações: autorização por dispositivo e renovação, descoberta de serviços, documentos GraphQL, propriedade por loja, CloudMatch, roteamento de sinalização, documento NVST suplementar do WebRTC e pacotes de entrada. Na versão 0.2, foi corrigida a seleção `idToken ?: accessToken` também observada em `OpenNowViewModel.kt`. A implementação aqui usa Java/JSON e uma interface Android nova. O NVST suplementar **não** transforma este cliente no transporte RTSPS nativo do OpenNOW para desktop.

- Na 0.6, `SurfaceDecoder`/`SurfaceFrame` adaptam o caminho de saída MediaCodec para superfície e o ciclo de vida do buffer opaco real, com apresentação/descarte único e sem pixels YUV simulados. `InputEncoder.text` adapta o enquadramento de texto UTF-8 de `StreamInputEncoder.kt` (`0x22`, tipo 23 little-endian e blocos de até 1016 bytes sem cortar caracteres). Pedido de keyframe e limites de referências seguem as mesmas referências MIT. O código é reimplementado em Java, com limites de fila e integração de interface próprios.

## OpenNOW Web

- https://github.com/OpenCloudGaming/Opennow-web
- Revisão consultada: `09b61b6b88ba53f73a090c74f9c294374e0e467a`.
- Na 0.10, também foram consultados os campos do pedido RESUME e o formato de coordenadas absolutas em cloudmatchSessionRequest.ts, cloudmatch.ts e inputProtocol.ts; a implementação e o ciclo de vida são próprios em Java.
- Referência para arquitetura de autorização e sinalização; o frontend React, servidor Express e seus arquivos não são incorporados. Não é necessário hospedar backend próprio.

## xStreaming

- https://github.com/Geocld/XStreaming
- Revisão consultada: `9e6a21251c379c861cc48f105530895dab9180c3`.
- Licença upstream: AGPL-3.0. Foi consultado para entender a separação dos motores e as políticas de baixa latência. **Nenhum código, recurso visual ou módulo de Xbox desse projeto foi copiado.** O decodificador deste projeto foi escrito para Android MediaCodec usando APIs documentadas, sem os ajustes por reflexão/chaves proprietárias do xStreaming.

## Dependências distribuídas no APK

| Componente | Versão | Licença / referência |
|---|---|---|
| WebRTC Android SDK | 144.7559.14 | BSD-3-Clause e avisos dos componentes WebRTC; https://github.com/webrtc-sdk/android |
| AndroidX RecyclerView e transitivas | 1.3.2 | Apache-2.0; https://developer.android.com/jetpack/androidx/releases/recyclerview |
| OkHttp / Okio e transitivas | 4.12.0 | Apache-2.0; https://github.com/square/okhttp |
| ZXing Core | 3.5.3 | Apache-2.0; https://github.com/zxing/zxing |
| Gradle Wrapper | 8.10.2 | Apache-2.0; https://github.com/gradle/gradle |
| JUnit (somente testes) | 4.13.2 | EPL-1.0 |
| Mockito (somente testes) | 5.14.2 | MIT; https://github.com/mockito/mockito |
| MockWebServer / okhttp-tls (somente testes) | 4.12.0 | Apache-2.0; https://github.com/square/okhttp |
| org.json (somente testes) | 20240303 | JSON license |

As capas, nomes, textos promocionais e marcas dos jogos pertencem aos respectivos titulares. São carregados dos metadados e serviços remotos; não há um catálogo de jogos falsos embutido. NVIDIA e GeForce NOW são marcas da NVIDIA. O aplicativo não é oficial nem afiliado à NVIDIA.

## Referências adicionais da 0.14

- OpenNOW: a49b124ce31e87113fd4961f6a321fbf0ad1fdc5, cloudmatch.rs e webrtc/signaling.rs, consultados para estados de reivindicação, polling e peer removido. A recuperação Java e seus testes são próprios; o protocolo e as licenças históricos já atribuídos foram mantidos.
- xStreaming: mesma revisão 9e6a21251c379c861cc48f105530895dab9180c3, NanoMediaCodecHelper/LowLatencyVideoDecoder/Factory e configuração; consulta de capacidades e prioridades. Nenhum código ou módulo AGPL foi incorporado.
- Nuvem-TV: https://github.com/LeuAndrade/Nuvem-TV, revisão 3d3efd9b84d15a7197babe24e544526e2884e6cb; LowLatencyDecoderFactory e NativePlayer consultados para hardware e FEATURE_LowLatency. Nenhum código, recurso, autenticação ou protocolo xCloud desse projeto foi incorporado.
- O letreiro vetorial do banner foi desenhado com contornos de DejaVu Sans. Não há arquivo de fonte no APK. Aviso do pacote da fonte em licenses/DejaVu-copyright.txt. Símbolo, navegação e demais ícones são código/vetores próprios.

## Referências adicionais da 0.18

OpenNOW main a49b124ce31e87113fd4961f6a321fbf0ad1fdc5: cloudmatch.rs, webrtc/signaling.rs e webrtc/mod.rs revistos para roteamento, endpoints e contrato legado. Avisos MIT existentes preservados. O bundle web público NVIDIA foi somente inspecionado para o contrato 3.0; não foi incorporado ou redistribuído. Implementação Java, integração e testes desta revisão são próprios. URLs/hashes em docs/FILA_E_INICIO.md.

## Referências adicionais da 0.19

OpenNOW Android 1bea55a39c23d66780b9aedd85a5d967173d58d6, GfnApi.kt/GfnSessionConnections.kt/StreamSignaling.kt, revistos para alocação WebRTC, contrato de parceiros e separação de endpoints. Avisos MIT existentes preservados. O adaptador público NVIDIA foi apenas inspecionado para o protocolo seguro 5; seu bundle não integra o projeto. Implementação Java e testes desta revisão são próprios.
