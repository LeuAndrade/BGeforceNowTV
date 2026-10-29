# Conexão — BGeforce Now TV 0.19.0

A foto da 0.18 confirma WebSocket aberto, 12 mensagens, atribuição de peer e ACK 3, sem oferta SDP após três tentativas. Isso localiza o problema antes do ICE, do vídeo e do decoder. A foto não contém os descritores originais nem os corpos dos pacotes; não prova isoladamente a causa exata da sessão do usuário.

## Correções desta revisão

- O cliente NVIDIA atual marca como seguro o descritor de sinalização usage=14/appLevelProtocol=5 e usa sua porta. A 0.18 reconhecia porta explícita apenas para protocolo 4, podendo acessar 443 enquanto o servidor anunciava outro canal. A 0.19 mantém a porta do protocolo 5, dá prioridade a esse descritor e não usa sua porta TLS como fallback UDP de vídeo. Uma URL WSS/HTTPS absoluta continua preservando sua própria porta. Descritores RTSP continuam usando o caminho de compatibilidade separado.
- Os dois motores locais são clientes WebRTC. A seleção Native passou a pedir, na NVIDIA, BROWSER/WEBRTC com clientPlatformName=browser, como a opção de alocação browser do OpenNOW. Isso não carrega um site nem muda o decoder: Native continua WebRTC Android + MediaCodec. O contrato NATIVE/NVIDIA-CLASSIC de parceiros é preservado. Não se altera resolução/FPS/bitrate/codec escolhido.
- A identidade é selecionada pelo serviço autenticado da conta, e permanece durante descoberta, fila, criação, consultas e retomada. Não é reclassificada como parceiro apenas porque o controlador usa outro domínio.
- O diagnóstico inclui canal (usage/protocolo/porta), confirmação do nome do peer e os campos de transporte declarados pelo serviço. Copiar erro acrescenta os últimos 12 eventos de sinalização: tentativa, abertura, papel/conexão do peer e tipo de mensagem. Não contém corpos, SDP, tokens, nomes de peers ou IDs de sessão. Nenhum dado é enviado automaticamente.

A sinalização 3.0, as confirmações, o fallback legado 2, TLS, cancelamento e os orçamentos finitos da 0.18 permanecem. Os arquivos centrais de mídia, biblioteca WebRTC, superfícies, controles e asset WebView não foram alterados.

## Fontes verificadas

- Cliente NVIDIA público atual: https://play.geforcenow.com/mall/vendor.67f3eddbbf4416ca.js . O adaptador de sessão converte appLevelProtocol 5 em sinalização segura e transfere ip/port/resourcePath. O cliente web declara streamer WEBRTC. Bundle apenas inspecionado, sem execução ou redistribuição. SHA-256: f175c303fb44476f15a3f75eb6bb5438ef93e4f9c50c29067002c447fd6ae3e1.
- OpenNOW Android, commit 1bea55a39c23d66780b9aedd85a5d967173d58d6: GfnApi.kt (identidades de alocação, modo browser, diferenças entre NVST e WebRTC), GfnSessionConnections.kt (separação de sinalização/mídia) e StreamSignaling.kt (contrato legado de peer/SDP). URLs e hashes em validation/REFERENCIAS_CONEXAO_0.19.json. O OpenNOW Android consultado mantém 443 para descritores relativos; o suporte explícito ao protocolo seguro 5 nesta revisão vem do adaptador NVIDIA, não dessa regra legada.

## Verificação e limites

Os cenários de regressão exercitam porta 5, prioridade do canal seguro, separação de UDP e pedido WebRTC com renderer Native. A fixture de sinalização TLS agora recebe o endpoint de um descritor protocolo 5 antes de confirmar peer, receber oferta e encaminhar resposta. A retomada é testada com controlador fora do domínio do broker e uma única mutação. Testes de parceiros, fila, autenticação e player são mantidos.

Esses ensaios usam servidor local e payloads sintéticos; não equivalem a uma sessão autenticada, à fila real ou a um teste físico na TV. Não há garantia de que todos os erros do serviço foram eliminados. As contagens, assinatura e comparação dos binários constam em VERIFICACAO.md.
