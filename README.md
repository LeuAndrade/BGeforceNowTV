# BGeforce Now TV — 0.15.0

Cliente independente para Android TV, com interface de catálogo e biblioteca, login pelo celular e streaming WebRTC Native ou WebView. Não é um aplicativo oficial da NVIDIA. O projeto tem estrutura própria e pode ser colocado num repositório novo, sem fork.

O padrão permanece **Native · Compatível · 1280×720 · 60 FPS · H.264 · 8 Mbps**. O caminho Compatível e o perfil padrão que já funcionaram na TV foram preservados. 60 FPS é o alvo: rede, servidor, driver e capacidade do aparelho determinam a taxa real.

## Correções da 0.15 — fila e início do streaming

- A posição usa o progresso de seatSetupInfo/sessionProgress antes do campo inicial do broker. Valores nulos, negativos ou fracionários não viram posição zero.
- Respostas alternadas de réplicas não fazem a posição voltar a cada consulta. Um aumento real é aceito após duas leituras consecutivas iguais; trocar de sessão limpa esse filtro.
- Posição zero mostra **Preparando sessão…**; não equivale à chegada de vídeo. seatSetupStep=1 continua sendo fila mesmo com status 2 e endpoint já preenchido.
- Na passagem para pronto, o cliente consulta uma vez o servidor de jogo autenticado para obter a conexão atual. A rota que respondeu fica priorizada nas consultas seguintes.
- Mudança do endpoint antes da oferta pode reconstruir o transporte, com limite, sem criar outra sessão. A identidade da conexão fica vinculada ao snapshot usado para iniciar o player.
- Um WebSocket aberto sem oferta SDP por 12 segundos entra numa recuperação limitada à sinalização: até três conexões no orçamento normal. A última usa peer novo/protocolo 2. Não há POST/RESUME/DELETE nessa recuperação; erros de TLS/autorização não são repetidos.
- Padrão 720p60, Full HD opcional, decoder, renderização e envio de controles permanecem. Não houve teste autenticado na TV do usuário; os testes controlados não garantem que toda falha do provedor tenha sido eliminada.

Veja [FILA_E_INICIO.md](docs/FILA_E_INICIO.md) e [VERIFICACAO.md](docs/VERIFICACAO.md).

## Novidades da 0.14

- Interface de TV refeita a partir do modelo enviado: navegação superior central, destaque amplo com navegação manual/automática, foco azul, capas quadradas, fileiras horizontais e configurações com painel lateral de contexto. Login, detalhes, conexões e diálogos seguem a mesma paleta. Logo própria e banner 16:9 com nome para o launcher Android TV, além de ícone adaptativo.
- Transições curtas de opacidade/deslocamento e foco, sem relayout por quadro, blur ou vídeo promocional. O catálogo usa RecyclerView e o orçamento de imagens anterior; animações e capas são liberadas ao entrar no player. A preferência de animações do Android é respeitada.
- “Volte a jogar” saiu do Início. “Minha biblioteca” aparece uma vez, priorizando os jogos jogados recentemente. Campanhas, categorias e gêneros continuam vindo dos metadados do provedor.
- Ao selecionar um jogo com sessão identificada, aparecem **Retomar sessão** e, logo abaixo, **Fechar sessão**. Encerrar exige confirmação, valida sessão/jogo e faz um DELETE sem repetição automática. Uma falha incerta conserva a informação da sessão para nova consulta.
- Consultas de uma retomada priorizam a rota que respondeu à reivindicação, com alternativas autenticadas limitadas para erros transitórios, incluindo HTTP 500. Falhas temporárias de GET não encerram um vídeo já ativo. Sessão ausente e falhas de autorização continuam explícitas.
- Se uma reconexão recebe peerRemoved antes da oferta, há uma tentativa de peer novo/protocolo 2 dentro do mesmo orçamento de três conexões. Não se repete RESUME, criação ou encerramento nessa recuperação. Uma repetição manual anterior ao primeiro quadro também limpa a identidade inválida.
- O endpoint de mídia autenticado pode corrigir um candidato UDP de host público antigo. Candidatos srflx públicos e relays permanecem. A inicialização pode atualizar/reabrir uma vez o transporte da mesma sessão após o limite sem primeiro quadro; a mensagem final informa SDP, ICE, candidatos e track. Um pedido inicial de keyframe é feito após a resposta SDP.
- Perfil opcional **Full HD · 1080p60 · H.264 · 15 Mbps**, com verificação de suporte de hardware antes de selecionar. Mantém 1080p60 também em aparelhos com pouca RAM; não reduz silenciosamente a qualidade. Saída direta é o padrão desse perfil quando não há outra escolha de decoder salva.
- No decoder direto, um buffer de entrada já disponível recebe os bytes codificados diretamente, evitando uma cópia intermediária. Quadros que precisam aguardar mantêm uma cópia independente e os limites de memória anteriores. A seleção avançada prioriza hardware que anuncie baixa latência no Android 11+, com fallback de hardware existente.

O padrão continua **720p60/8 Mbps/Compatível**, e as otimizações dos controles da 0.13 foram preservadas. Os testes não equivalem a uma partida autenticada em TV física nem medem ganho real de FPS/decode. Veja [NOVO_LAYOUT_E_1080P.md](docs/NOVO_LAYOUT_E_1080P.md) e [VERIFICACAO.md](docs/VERIFICACAO.md).

## Novidades da 0.13

- Botões repetidos sem alteração de estado deixam de rearmar o timer do controle. Pressionar, combinar e soltar continuam imediatos, inclusive durante atualizações analógicas.
- Uma única chamada pendente conserva o prazo de envio; amostras analógicas repetidas não percorrem a fila da UI a cada evento. Keepalives ativos de 100 ms e neutros de 1 segundo foram preservados.
- Consultas de dispositivo saíram do caminho normal de gameplay. Direcional/OK e eventos compatíveis de controles usam classificação por fonte e um cache atualizado ao conectar/remover; teclado físico continua funcionando, inclusive com mouse virtual.
- O pacote do controle escreve estado e envelope numa única alocação, sem copiar um segundo array. Cada comando segue independente e o protocolo mantém os mesmos bytes.
- A correção de Retomar da 0.12 continua presente. Padrão 720p60/H.264/8 Mbps, decoder, layout e opções de qualidade foram mantidos. Veja [CONTROLE_E_FPS.md](docs/CONTROLE_E_FPS.md).

## Correção de retomada da 0.12, preservada

- Retomar uma sessão existente começa com conexão WebRTC nova, protocolo 2, mesmo quando houve RESUME no CloudMatch. A retomada do jogo e a reconexão do socket têm estados separados.
- Uma nova tentativa do mesmo transporte mantém o peer_id e usa protocolo 3.0/reconnect. Uma sessão ou servidor diferente recebe identidade nova.
- Na retomada, falhas transitórias anteriores ao SDP permitem até duas novas tentativas de WebSocket, após 1 e 2 segundos. Isso não repete RESUME, não cria outra sessão e não altera a fila. Erros de autenticação/certificado não entram nesse ciclo.
- A rota WSS pode usar o IP público informado na resposta autenticada para o mesmo hostname de sinalização, conservando URL, SNI, verificação do certificado e do hostname.
- Mensagens de sinalização incluem classe/causa da exceção, servidor, protocolo e tentativa, com dados sensíveis ocultos. Heartbeat e callbacks antigos são interrompidos ao encerrar ou trocar o socket.
- **ARMv7, ARM64 e Universal** gerados na mesma revisão, com a mesma chave principal e opções de ZIP para instalação. Veja [RETOMADA_DA_SESSAO.md](docs/RETOMADA_DA_SESSAO.md).

## Mudanças da 0.11 preservadas

- APKs completos com nomes curtos, assinatura v1/v2 explícita e a mesma chave principal. Opção de instalação em ZIP com hash do APK interno. MinSdk 23 e pacote com.bgeforce.tv.main preservados.
- O workflow principal verifica assinatura, certificado, integridade ZIP, alinhamento, manifesto, versão, ABIs e DEX antes de disponibilizar APKs. Compilações unsigned não são publicadas como instaláveis.
- Estatísticas opcionais deixam de encerrar o streaming se getStats falhar. Intervalos nulos, reinício dos contadores, mudança de SSRC e valores não finitos são tratados nos dois players.
- O painel separa o alvo da sessão da resolução/FPS decodificados. O tempo de decodificação usa o intervalo recente; valores ainda indisponíveis aparecem como travessão. Nenhuma resolução, bitrate, FPS ou decoder foi reduzido nesta revisão.
- Verificação de instalação e atualização no Android 10, além dos testes de código. Veja [INSTALACAO.md](docs/INSTALACAO.md) e [VERIFICACAO.md](docs/VERIFICACAO.md) para os resultados e limites.

## Controles e retomada da 0.10, preservados

- Voltar durante a partida abre uma barra compacta no canto superior esquerdo, com teclado, mouse virtual, áudio, três pontos e retorno ao jogo. Os ícones são vetores locais; a barra não redesenha o vídeo quando está escondida.
- Mouse virtual pelo controle da TV: setas/analógico movem, OK/A clica, B/X faz clique direito. Voltar desativa esse modo. O cursor envia posições e cliques reais pelo canal de entrada do streaming.
- Teclado e mouse físicos com reconhecimento ao conectar/remover. Há opções para habilitá-los, capturar o mouse em jogos e aceitar toques. Movimentos rápidos são agrupados em intervalos de cerca de 16 ms sem perder o deslocamento relativo; cliques e teclas mantêm a ordem.
- Toques na imagem do jogo viram movimentos absolutos, clique e arraste. O cálculo usa a área de vídeo e exclui as faixas pretas. A resolução real informada pelo player atualiza as coordenadas.
- O menu de áudio permite ligar/desligar o som do jogo e silenciar o microfone já habilitado antes da sessão. Os três pontos contêm estatísticas, Colar e Sair do jogo. Colar lê a área de transferência apenas ao selecionar essa ação e envia texto UTF-8, sem armazená-lo.
- Retornar à sessão aparece nos detalhes do jogo quando há sessão ativa da conta, inclusive em outro dispositivo. Uma leitura confirma o estado antes do pedido RESUME; esse caminho não cria outra sessão. O servidor pode desconectar o outro cliente ao transferir o jogo para esta TV.
- Sair oferece desconectar e manter a sessão ou encerrá-la de fato. Perda de visibilidade/fechamento comum não envia DELETE automaticamente. A retomada depende de o provedor ainda manter a sessão ativa.
- Diagnóstico do player removido das configurações e do popup de abertura. Mensagens de erro operacional continuam disponíveis quando uma ação falha.

## Otimização preservando as funções

Além do agrupamento analógico, liberação de capas/listas e encerramento de workers ociosos da 0.9, esta revisão agrupa movimentos de mouse, evita loops do cursor parado, interrompe consultas do catálogo durante o streaming, solicita Wi-Fi de baixa latência no Android 10+ com fallback e informa ao Android a cadência de exibição desejada. Nenhuma dessas solicitações é condição para iniciar o jogo.

O decoder Compatível, o hardware scaler, o áudio estéreo e as opções de qualidade foram preservados. Não há mudança automática para decoder próprio, redução silenciosa de resolução/FPS, descarte de cliques ou desativação de funções para obter FPS. Leia [VERIFICACAO.md](docs/VERIFICACAO.md) para os ensaios e limites; não foi medido FPS sustentado da 0.14 numa TV física.

## Catálogo, conta e configurações

- Login por QR/código no celular, restauração da conta, renovação após 401 e sair da conta. Credenciais cifradas com Android Keystore; não há backend próprio.
- Início com destaques oficiais manuais/automáticos, fileiras de capas, biblioteca com jogados recentemente, gêneros e catálogo. Busca em Início/Biblioteca/Catálogo, filtros, ordem A–Z e paginação. Categorias e campanhas variam por região/conta.
- Metadados oficiais, variantes por loja, propriedade sincronizada e marcação manual. Marcar que possui um jogo não compra a licença.
- Conexões com lojas por URL/QR no celular ou página interna; algumas lojas restringem WebView e código curto só aparece quando fornecido pelo serviço.
- Native com libwebrtc/MediaCodec e WebView com player WebRTC local. Decoder Compatível recomendado; decoders próprios continuam como escolhas avançadas.
- Perfis Padrão 720p60/8 Mbps, Full HD 1080p60/15 Mbps, Leve 720p30/4 Mbps, Ultraleve 480p30/3 Mbps e Personalizado; resolução, bitrate, FPS, codec, servidor, adaptação, estatísticas, zona morta e microfone preservados.
- Fila com capa e posição real da sessão; sonda TCP dos servidores e RTT ICE nas estatísticas. Uma estimativa externa de fila pode estar indisponível.
- Idioma do sistema por padrão e seleção de 14 idiomas; alguns textos novos/técnicos usam português/inglês como fallback.

O fluxo obrigatório de publicidade de algumas sessões gratuitas ainda exige o cliente oficial do provedor. Retomar uma sessão não renova o tempo do plano nem impede expiração por inatividade. Uma sessão já criada conserva seus parâmetros do servidor: um perfil acima da capacidade da TV pode exigir encerrar essa sessão e iniciar outra em 720p60.

## Instalação e compilação

Os APKs 0.14 atualizam os principais 0.7, 0.8, 0.9, 0.10, 0.11, 0.12 e 0.13 desta conversa, com pacote **com.bgeforce.tv.main** e a mesma assinatura. Instale por cima para manter login e configurações. Para a TV identificada como ARMv7, use o APK ARMv7. O antigo pacote .preview da 0.6 é separado.

[Passo a passo pelo GitHub](docs/GITHUB_PASSO_A_PASSO.md). A chave principal entregue separadamente com a 0.7 continua válida: mantenha-a privada e preserve os secrets existentes.

Com Java 17, SDK 35 e Node:

```bash
chmod +x gradlew
node scripts/check-player.mjs
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Debug usa o pacote separado .main.dev. Release precisa das variáveis BGFN_KEYSTORE/BGFN_STORE_PASSWORD/BGFN_KEY_ALIAS/BGFN_KEY_PASSWORD; sem assinatura gera APK unsigned. JUnit/Mockito são usados só nos testes. Instruções de uso em [OTIMIZACAO_E_TECLADO.md](docs/OTIMIZACAO_E_TECLADO.md) e atribuições em [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
