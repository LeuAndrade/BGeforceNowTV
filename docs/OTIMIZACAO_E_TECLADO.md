> Atualização 0.14: interface de TV renovada, perfil Full HD opcional, fechamento explícito nos detalhes e recuperação limitada de GET 500/peerRemoved/inicialização sem quadro. As otimizações dos controles da 0.13 permanecem. Leia NOVO_LAYOUT_E_1080P.md e VERIFICACAO.md para o comportamento atual e os testes. As seções abaixo registram também revisões anteriores.

# Nota da revisão 0.13

A 0.13 otimiza eventos e codificação do controle. Qualidade, decoder e funções anteriores mantidos. Leia CONTROLE_E_FPS.md.

# Revisão 0.12

A correção de Retomar modifica a sinalização e a identidade do transporte. O decoder, resolução/FPS/bitrate, controles e o caminho de entrada de vídeo da 0.11 permanecem iguais. As tentativas de WSS são limitadas à retomada antes da oferta SDP; não criam outro player de vídeo nem um segundo jogo. Consulte RETOMADA_DA_SESSAO.md.

# Revisão 0.11: desempenho e qualidade

O padrão permanece **720p60/H.264/8 Mbps, Native/Compatível**. A 0.11 preserva todos os controles e a retomada abaixo. Não foi alterado o motor de decodificação nem aplicada redução automática de resolução, bitrate ou FPS. O perfil Personalizado conserva seus valores também em aparelho de 1 GB; os perfis Leve/Ultraleve continuam escolhas explícitas do usuário.

As estatísticas são coletadas apenas quando ligadas, a cada dois segundos. Se a coleta falhar, ela não encerra o player. O painel distingue alvo da sessão e resultado decodificado; decode ms agora usa o intervalo recente, evitando que uma média desde o começo esconda picos. Contador reiniciado, troca de SSRC e relógio repetido não geram taxas inválidas. Sem amostra suficiente, aparece —.

FPS decodificado é informação do WebRTC, não medição garantida da apresentação do painel da TV. Solicitar 60 FPS não elimina limites de rede, servidor, aquecimento ou hardware. A revisão não comprovou ganho de FPS numa partida física; conserva as otimizações existentes sem trocar qualidade por um número maior no painel. Consulte VERIFICACAO.md.

## Controles e retomada da 0.10, preservados na 0.11

O padrão continua **1280×720, 60 FPS, H.264, 8 Mbps, Native e decoder Compatível**. O app preserva escolhas explícitas de qualidade. 60 FPS é o alvo solicitado, sem garantia de estabilidade absoluta em todo hardware/rede.

## Barra durante a partida

Pressione Voltar para abrir a barra no canto superior esquerdo, conforme a referência enviada. A seta na barra ou Voltar novamente fecha a barra e devolve o foco ao jogo.

| Botão | Ação |
|---|---|
| Teclado | Abre a janela de escrita; selecione primeiro o campo dentro do jogo. Enviar texto manda UTF-8; Enter também envia Enter. Tab, Esc e apagar continuam disponíveis |
| Mouse | Ativa o ponteiro pelo controle da TV. Setas/analógico movem; OK/A clica; B/X faz clique direito. Voltar desativa esse modo |
| Áudio | Abre opções de som do jogo e microfone. Mutar o jogo não desliga o microfone e vice-versa |
| Três pontos | Ativar/desativar estatísticas, Colar, Sair do jogo |
| Seta | Retorna ao vídeo e aos controles normais |

Colar só lê a área de transferência depois de selecionar essa ação; o texto não é guardado pelo app. Cada envio aceita até 4096 caracteres. Texto da janela do teclado fica oculto por padrão, com opção Mostrar texto. O jogo pode filtrar texto ou exigir foco no campo.

Ative o microfone nas configurações antes de iniciar a sessão e conceda a permissão quando solicitada. É necessária uma entrada de áudio conectada. A opção da barra permite silenciar/reativar a track que foi iniciada com essa sessão.

## Teclado, mouse físico e toques

Em Configurações → Jogabilidade há Teclado e mouse físicos (ligado por padrão), Capturar mouse durante o jogo (desligado por padrão) e Toques na tela do jogo (ligado). Conecte os periféricos ao Android, por USB/Bluetooth; o app recebe as entradas e reconhece conexões/remoções durante o player.

Sem captura, o mouse usa posições na imagem do jogo. Ative Capturar mouse para movimento relativo e câmera contínua em jogos que prendem o cursor; isso depende de Android 8+. Voltar libera a captura ao abrir a barra. Manter o botão de teclado da barra pressionado também abre as opções de teclado/mouse sem sair da partida.

Toque toca/clica e permite arrastar; somente a área real da imagem é considerada. As faixas pretas não deslocam as coordenadas do jogo. Este é um ponteiro/mouse para um jogo de PC, não um controle virtual multitoque completo.

O app solta teclas, botões e estado do controle ao abrir uma janela, perder a tela ou remover um dispositivo. O teclado físico usa eventos de teclas; símbolos/layout dependem também do layout da sessão remota. A janela de escrita aceita texto Unicode pelo teclado do Android.

## Retornar a um jogo em andamento

A conta é consultada ao abrir/retornar ao aplicativo e periodicamente enquanto o catálogo está visível. Havendo sessão ativa, o botão Retornar à sessão aparece no Início e nos detalhes do jogo correspondente. A detecção não filtra pelo aparelho que criou a sessão, permitindo retornar de outro dispositivo na mesma conta/provedor.

Ao selecionar o retorno, o app consulta o estado atual e envia RESUME, usando o servidor da sessão. Não cria outro jogo nesse caminho. Sessão na fila é acompanhada sem enviar RESUME prematuramente. Sessões encerradas/expiradas são recusadas, sem substituição silenciosa por uma sessão nova.

O pedido de retomada não inclui resolução, FPS ou codec novos. Quando o servidor informa o monitor negociado, esse perfil é usado apenas na memória do player; suas configurações padrão continuam salvas. Caso a sessão tenha sido criada num perfil que a TV não suporta, encerre-a e inicie outra com o perfil adequado. O provedor pode desconectar o outro aparelho ao transferir a sessão.

Em Sair do jogo, escolha Desconectar e manter sessão para voltar ao catálogo e poder retomar enquanto o servidor permitir. Encerrar sessão envia o pedido de término. Fechar o processo, trocar de dispositivo ou desconectar não garante conservação indefinida: o servidor pode encerrar a sessão por inatividade ou pelas regras do plano.

## O que foi otimizado

- Mantidos o decoder de hardware Compatível e o hardware scaler que funcionaram na 0.9.
- Mouse com agrupamento de cerca de 16 ms: deslocamentos relativos são somados e posições absolutas usam o estado mais recente. Movimento pendente é enviado antes do clique. O cursor virtual não tem loop quando parado.
- Barra composta por ícones vetoriais e um cursor pequeno; sem imagens externas ou animação permanente. Estatísticas continuam sem coleta quando desligadas.
- Android 10+ solicita Wi-Fi de baixa latência, com fallback para o lock anterior; falha/ausência desse recurso não impede a transmissão. O efeito depende do Wi-Fi/driver e não muda Ethernet.
- O Android recebe a cadência desejada da janela e, no Native Android 11+, da superfície. São sugestões à plataforma; não mudam a resolução da TV nem obrigam um modo de tela incompatível.
- Consultas de sessão seguem em 3 s na fila/carregamento e 10 s na partida. Descoberta de sessões do catálogo para durante o streaming. Os heartbeats foram preservados.
- Preservadas as otimizações da 0.9: controle analógico agrupado, zona morta em memória, comandos WebView em lotes, liberação das capas/listas e encerramento dos dois workers de catálogo após 30 s ociosos.

Os testes de código verificam ordem, limites, coordenadas, retomada e trabalho evitado. Não medem FPS/RAM sustentados numa TV física. Não há promessa de eliminar toda queda para 40 FPS; servidor, rede, temperatura e capacidade do decoder também podem limitar a taxa.

## Instalar

A 0.10 atualiza os APKs principais 0.7/0.8/0.9 desta conversa com o mesmo certificado. Instale por cima para preservar dados. A TV fotografada é ARMv7. A assinatura privada principal da 0.7 continua válida para seus builds no GitHub. O diagnóstico foi removido das configurações; erros operacionais ainda mostram Tentar novamente/Copiar erro quando necessário.
