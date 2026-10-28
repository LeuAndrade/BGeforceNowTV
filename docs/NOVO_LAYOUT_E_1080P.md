> Atualização 0.15: veja [FILA_E_INICIO.md](FILA_E_INICIO.md) para a correção de fila e espera de SDP e [VERIFICACAO.md](VERIFICACAO.md) para os testes atuais. O texto abaixo documenta a revisão anterior.

# BGeforce Now TV 0.14 — interface e 1080p60

## Navegação

O topo concentra Início, Biblioteca, Catálogo e Configurações. A busca aparece só nas três primeiras abas. Destaques têm rotação automática pausada ao receber foco e troca manual pelas setas/controle. O destaque volta inteiro ao retornar à parte superior. Capas têm nome e loja; a biblioteca prioriza a data de última partida. A seção Volte a jogar foi removida, incluindo equivalentes comuns dos metadados. Campanhas e jogos continuam sendo os do catálogo oficial da região/conta, sem jogos fictícios embutidos.

O visual segue o modelo fornecido: fundo escuro, destaque largo, navegação em cápsula, foco azul, botões claros e fileiras de capas. Detalhes usam fundo do jogo e ações organizadas; configurações, rede, conexões, login/QR e diálogos usam a mesma identidade. O banner do launcher Android TV agora é 16:9, com símbolo próprio e letreiro BGeforce NOW TV. O ícone adaptativo tem fallback para Android 6/7.

As animações de foco/entrada duram cerca de 100–170 ms e alteram propriedades visuais; não mudam o layout a cada quadro. Não há animação contínua de fundo. RecyclerView continua reciclando os cartões, imagens mantêm os limites RGB565 de 3/6 MiB e são liberadas junto com o catálogo ao iniciar streaming. O player não recebe essas animações.

## Retomar ou fechar

Abra os detalhes do jogo e aguarde a consulta da conta. Quando a sessão correspondente é identificada, o botão principal passa a ser Retomar sessão; Fechar sessão fica abaixo. Fechar apresenta confirmação e consulta novamente o ID/jogo antes do encerramento. Cancelar não envia DELETE. Uma tentativa não repete automaticamente esse comando se o servidor retornar erro.

## Selecionar 1080p60

1. Abra Configurações → Perfil de desempenho.
2. Escolha **Full HD · 1080p60**.
3. O aplicativo verifica se há decoder H.264 por hardware anunciado para 1920×1080 a 60 FPS. Se não houver, informa isso e mantém o perfil atual.
4. O perfil pede H.264, 1920×1080, 60 FPS e 15 Mbps. Resolução/FPS e estatísticas continuam mostrando os valores reais recebidos/decodificados separadamente do alvo.
5. Quando não existe escolha de saída salva, Full HD usa a saída direta MediaCodec. Se já havia escolhido Compatível ou Baixa latência própria, a preferência é respeitada. A opção fica em Jogabilidade → Decodificador de hardware.
6. Uma sessão existente conserva os parâmetros do servidor. Para iniciar outra com o novo perfil, salve o progresso e use Fechar sessão antes de Jogar agora.
7. O perfil Padrão · 720p60 continua disponível e permanece o padrão em instalação nova.

## O que mudou no decode

A via direta entrega MediaCodec à SurfaceView e mantém o fallback de hardware anterior. Quando já há um buffer de entrada pronto, os dados codificados são copiados diretamente para ele; a cópia intermediária só é necessária quando o quadro precisa aguardar. Os testes verificam que o quadro em espera conserva os próprios bytes mesmo se a origem for alterada. Os limites de fila/memória e o descarte/apresentação únicos dos buffers de saída não foram relaxados.

A seleção avançada prioriza decoders de hardware que declarem FEATURE_LowLatency no Android 11+, com KEY_LOW_LATENCY somente quando suportado. Prioridade em tempo real e operating rate seguem o alvo. Não há chaves privadas de fabricantes nem patch por reflexão do WebRTC. No Android 10, essa opção padronizada de baixa latência do Android 11 não está disponível; isso não impede o caminho nativo existente.

Referências consultadas: xStreaming 9e6a21251c379c861cc48f105530895dab9180c3 (NanoMediaCodecHelper e LowLatencyVideoDecoder); Nuvem-TV 3d3efd9b84d15a7197babe24e544526e2884e6cb (LowLatencyDecoderFactory). Esses clientes usam outro serviço de jogos; seus protocolos de conta/stream não substituem o do GeForce NOW. As mudanças Java e a política de cópia/filas deste projeto são próprias. Não se incorporou código AGPL desses projetos.

## Limites da validação

Não houve partida autenticada, medição de decode em milissegundos ou FPS sustentado em TV ARM de 1 GB. O suporte informado pelo driver não garante desempenho sob aquecimento/carga. Não há promessa de 1080p60 sem qualquer queda em todo aparelho. O objetivo é reduzir trabalho evitável e conservar a qualidade solicitada; o HUD mantém as medições reais.

Referências oficiais: [MediaFormat](https://developer.android.com/reference/android/media/MediaFormat), [desempenho de codecs](https://developer.android.com/media/optimize/performance/codec), [banner/ícones de TV](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines).
