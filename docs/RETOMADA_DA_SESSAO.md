> Atualização 0.15: veja [FILA_E_INICIO.md](FILA_E_INICIO.md) para a correção de fila e espera de SDP e [VERIFICACAO.md](VERIFICACAO.md) para os testes atuais. O texto abaixo documenta a revisão anterior.

> Atualização 0.14: interface de TV renovada, perfil Full HD opcional, fechamento explícito nos detalhes e recuperação limitada de GET 500/peerRemoved/inicialização sem quadro. As otimizações dos controles da 0.13 permanecem. Leia NOVO_LAYOUT_E_1080P.md e VERIFICACAO.md para o comportamento atual e os testes. As seções abaixo registram também revisões anteriores.

# Nota da revisão 0.13

A correção de retomada descrita abaixo permanece na 0.13, junto das otimizações de entrada. O transporte e os pedidos REST dessa correção não foram alterados.

# Retomada de sessão — revisão 0.12

A foto recebida mostra a 0.11 em negociação/player, Native, 1280×720/60, H.264, seguida de Sinalização WebRTC · rede · sem detalhe da exceção. Ela confirma a etapa de falha, mas não informa se foi EOF, timeout, certificado, DNS ou outra causa de rede.

## Erro confirmado no código

O resultado de RESUME do CloudMatch era aplicado diretamente ao marcador de reconexão do WebSocket. Assim, o primeiro player nesta TV entrava com version=3.0/reconnect=1. Também era gerado outro peer_id sempre que a atividade reconstruía o objeto de sinalização.

O teste ResumeSignalingRegressionTest executa o caminho real de launch/ready da atividade com uma sessão reivindicada e respostas controladas: antes da correção, esperava false (primeira conexão nova) e recebia true (reconexão). Depois da correção, passa. A referência Android OpenNOW também separa início de transporte dos contadores de reconexão em Streaming.kt.

## Correção

- Primeira conexão do player, inclusive ao Retomar: protocolo 2, sem reconnect, identificação nova.
- Nova tentativa do mesmo transporte nesta atividade: mesma identificação de peer, protocolo 3.0 e reconnect=1.
- Mudança de sessão ou endpoint: identidade de transporte nova.
- Retomada com falha transitória antes de receber o SDP: até duas tentativas WSS, após 1 e 2 segundos, mantendo a sessão. Essa recuperação não envia novo POST, PUT RESUME ou DELETE. Depois do limite, aparece Tentar novamente com a causa legível.
- HTTP 401/403, certificado inválido e erro de protocolo não entram nas tentativas transitórias. O serviço ainda pode negar ou expirar a sessão.
- O IP público autenticado, quando associado ao mesmo hostname de sinalização, também orienta a rota WSS. TLS continua verificando certificado e hostname normalmente.
- Heartbeat encerrado ao falhar/fechar, callbacks antigos ignorados e confirmação do fechamento do servidor tratada. BYE/peerRemoved recebem mensagem explícita.

## Usar

1. Atualize para a 0.12 com o APK adequado ao Android: ARMv7, ARM64 ou Universal. A chave é a mesma, sem necessidade de sair da conta.
2. Abra Retornar à sessão no início ou nos detalhes do jogo. A sessão precisa continuar ativa no provedor.
3. Aguarde a consulta e a negociação. Ao transferir uma sessão de outro dispositivo, o servidor pode desconectar o player anterior.
4. Se a sessão estiver em fila, a retomada conserva a consulta da mesma sessão. Se expirou/foi encerrada, ela já não pode ser retomada.
5. Se houver erro, Copiar erro agora inclui a classe da exceção e informações de transporte, com identificadores sensíveis ocultos.

A sessão existente conserva seus parâmetros do servidor; retomar não recria o jogo para forçar outra resolução/codec. O padrão para sessões novas continua 720p60/H.264/8 Mbps, Native/Compatível. O decoder, os controles e as otimizações de mídia da 0.11 não foram alterados.

## Validação e limites

Os testes locais incluem WebSocket sobre TLS real, interrupção antes da oferta, reconexão com peer estável, limite de tentativas, cancelamento, recusa HTTP, certificado não confiável, encerramento após oferta e peerRemoved. As respostas são de um servidor de teste; não foi feita uma retomada autenticada no servidor GFN da TV do usuário. A correção do erro de lógica foi comprovada; a causa exata da exceção de rede sem mensagem da foto permanece sem confirmação.

Resultados completos, instalação no Android e hashes estão em VERIFICACAO.md. Fontes usadas como referência: OpenNOW 1bea55a39c23d66780b9aedd85a5d967173d58d6, StreamSignaling.kt e Streaming.kt, já atribuídas em THIRD_PARTY_NOTICES.md.
