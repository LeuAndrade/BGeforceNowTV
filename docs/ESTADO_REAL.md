# Estado real da versão 0.15.0

Corrige leitura e confirmação da posição da fila, espera em zero, consulta ao nó atribuído antes do player, manutenção da rota de leitura, mudança limitada do endpoint antes do SDP e prazo finito para WebSocket aberto sem oferta. Não altera a qualidade/decoder padrão nem o envio de controles da 0.14.

258 JUnit e 59 verificações JS passaram; lint sem erros (31 avisos). A regressão de fila/prontidão reproduziu quatro falhas na fonte 0.14 e passa na 0.15. APKs release gerados e assinatura/pacote/ABI/DEX/alinhamento/ZIP verificados. Não houve execução autenticada em TV física, medição de FPS/decode ou novo ensaio Android nesta revisão. Ensaios Android 0.14 em validation são históricos. Não há garantia de que todo erro do provedor desapareça.

Veja FILA_E_INICIO.md para detalhes e instalação. O padrão continua 720p60/H.264/8 Mbps/Compatível; Full HD opcional permanece.
