# Estado real — 0.19.0

Corrigidos porta/prioridade do canal seguro protocolo 5, separação entre sinalização TLS e fallback de mídia UDP, seleção da alocação WebRTC da NVIDIA para ambos os motores locais e identidade do provedor durante retomada. Ampliado o histórico de Copiar erro sem incluir corpos/tokens. Native permanece WebRTC Android + MediaCodec, com o mesmo decoder e configurações de vídeo.

Não houve partida autenticada, teste físico na TV ou medição de FPS/decode nesta revisão. A imagem localiza a falha antes da oferta SDP, mas não contém os descritores nem todos os pacotes da sessão real. Leia FILA_E_INICIO.md e VERIFICACAO.md para evidência, testes e limites.
