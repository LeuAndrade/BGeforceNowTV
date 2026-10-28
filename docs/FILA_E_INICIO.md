# Fila e início do streaming — 0.15.0

A imagem enviada mostra o player aguardando SDP, sem resposta, com ICE NEW e zero candidatos. Isso situa a falha antes de qualquer quadro para o decoder; não demonstra um gargalo de decodificação. Sem logs completos/autenticação não é possível atribuir a ocorrência na TV a uma causa única.

## Mudanças

1. A leitura prioriza os campos de progresso da fila antes do snapshot inicial no topo. Nulo não é zero; números inválidos são ignorados.
2. Respostas alternadas com posição antiga não desfazem cada avanço. Uma posição maior só é exibida após duas leituras consecutivas iguais. Isso não altera a fila do provedor e não congela permanentemente um aumento legítimo.
3. Ao chegar a zero, a tela mostra Preparando sessão. O player só inicia em status 2/3 com endpoint; seatSetupStep=1 mantém a espera. Zero não é tratado como vídeo pronto.
4. Quando uma consulta passa de fila para pronta, há uma consulta adicional limitada ao nó autenticado atribuído. O ID deve ser o mesmo; uma réplica ainda em fila não substitui a resposta pronta. Falhas transitórias nessa leitura mantêm a resposta anterior utilizável; autenticação/TLS continuam sendo verificadas.
5. A sinalização de sessão nova também usa o IP público autenticado pareado ao hostname exato, antes aplicado somente à retomada. URL/SNI/certificado e hostname continuam validados; IP privado/inválido ou de outro hostname não substitui DNS. A rota da última leitura válida fica priorizada. Um endpoint novo antes do SDP pode reiniciar o transporte no máximo duas vezes, preservando sessão e qualidade. Uma imagem ativa não é reiniciada por essa regra.
6. O socket é vinculado já no callback de abertura, evitando perder ACKs/mensagens numa resposta imediata. Se não chega oferta SDP em 12 s após abrir, tenta novamente apenas o WebSocket, dentro de três conexões. A última utiliza uma identidade nova; a oferta cancela o prazo. Fechar cancela os trabalhos. Não se repetem criação, RESUME ou DELETE por ausência de oferta.
7. O erro de inicialização também informa se o WebSocket abriu e quantas mensagens recebeu. Tokens e sessão continuam ocultos. Oferta vazia é erro explícito, não sucesso.

O layout, biblioteca, configurações, microfone, teclado/mouse/toque, retomada/fechamento e otimizações de decode/controle da 0.14 não foram retirados. O padrão segue Native/Compatível/720p60/H.264/8 Mbps; Full HD 1080p60/H.264/15 Mbps permanece opcional. Não é preciso reduzir para 30 FPS para aplicar esta correção.

## Como atualizar

Instale uma variante 0.15 sobre a principal atual, sem desinstalar nem apagar dados. ARMv7 corresponde a Android ARM de 32 bits; ARM64 a 64 bits; Universal serve quando não souber. O processador sozinho não determina a arquitetura do sistema. Os ZIPs precisam ser extraídos, nunca renomeados para APK.

Abra o mesmo jogo e acompanhe a fila. A indicação Preparando sessão em zero ainda pode durar enquanto o servidor prepara a máquina; não indica conexão já concluída. Esta versão não fura fila, renova tempo do plano nem substitui publicidade obrigatória do provedor. Se ainda falhar, Copiar erro permite distinguir falha de abertura WSS, socket aberto sem SDP e SDP recebido sem mídia.

## Verificação e limites

258 testes JUnit passaram (242 anteriores + 16 novos), incluindo trocas de rota com HTTP controlado e sinalização WebSocket sobre TLS validado. 59 verificações JavaScript passaram. Lint: zero erros e 31 avisos. APKs release ARMv7, ARM64, x86_64 e universal têm pacote/assinatura principal preservados e passam no verificador de assinatura v1/v2, alinhamento, manifesto, DEX, ABIs e integridade ZIP.

A regressão da leitura/prontidão foi reproduzida na fonte 0.14 com o novo teste de contrato e apenas a classe de filtro adicionados: 8 testes, 4 falhas esperadas; os mesmos 8 passam na 0.15. O teste de socket silencioso usa um prazo reduzido somente no construtor de ensaio, para verificar o caminho real de timeout sem aguardar 12 s em cada conexão.

Não houve partida autenticada nem acesso remoto à TV do usuário nesta revisão. As 25 verificações Android de player/entrada e 20 de interface registradas na pasta validation são históricas da 0.14, não execuções da 0.15. Não há benchmark físico de decode/FPS nem garantia de ausência de toda falha do servidor.

Referência técnica consultada: protocolo de sessões/sinalização do projeto OpenNOW e Opennow-web (fontes preservadas da pesquisa anterior). As correções de fila, confirmação de aumento e gestão da passagem de endpoint neste projeto são próprias.
