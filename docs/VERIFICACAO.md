# Verificação — BGeforce Now TV 0.19.0

303 testes Java aprovados; zero falhas, erros ou ignorados. 59 verificações JavaScript aprovadas. Lint: zero erros e 31 avisos. Build release concluído. Quatro APKs standalone (ARMv7, ARM64, Universal, x86_64) verificados: pacote com.bgeforce.tv.main, versão 19/0.19.0, mesma assinatura principal v1/v2, ABIs, alinhamento, DEX e integridade ZIP.

SecureChannel19RegressionTest reproduz quatro falhas na fonte entregue da 0.18: porta do protocolo seguro 5, separação de mídia UDP, prioridade do canal seguro e classe de alocação do renderer Native. O log em validation/ANTES_0.18_CANAL_SEGURO.txt registra essas falhas esperadas. Os mesmos cenários passam na 0.19. As expectativas antigas do contrato NVIDIA foram atualizadas para BROWSER/WEBRTC; o contrato de parceiros permanece NATIVE/NVIDIA-CLASSIC.

A fixture SignalingV3RegressionTest usa o descritor protocolo 5 para obter a URL e realiza TLS/WebSocket, atribuição/ACK, oferta e resposta roteada sintéticos. ProviderIdentity19Test verifica uma única mutação de retomada e a identidade NVIDIA em outro domínio de controlador. A privacidade do histórico também é verificada. Isso não é uma partida real nem uma conta autenticada.

Bibliotecas nativas, asset WebView e arquivos centrais de mídia/decoder/controles/Settings são idênticos aos da 0.18, conforme PIPELINE_PRESERVADO_0.19.json. Não houve teste físico na TV, medição de FPS/decode ou validação autenticada de GFN nesta revisão. Não se afirma que todos os erros do serviço foram eliminados. Veja FILA_E_INICIO.md.
