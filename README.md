<div align="center">

# BGeforce Now TV

### Seus jogos de PC na tela grande, com uma experiência feita para Android TV.

Catálogo visual · Biblioteca sincronizada · Login pelo celular · Streaming nativo

[Conheça os recursos](#-uma-biblioteca-de-jogos-na-sua-tv) · [Streaming](#-streaming-do-seu-jeito) · [Como começar](#-comece-a-jogar) · [Compilar pelo GitHub](docs/GITHUB_PASSO_A_PASSO.md)

</div>

O **BGeforce Now TV** é um cliente independente do GeForce NOW para Android TV. Ele reúne descoberta de jogos, organização da biblioteca e configurações de streaming em uma interface pensada para navegar com o controle remoto ou gamepad.

O projeto tem foco em aparelhos com poucos recursos, incluindo dispositivos de **1 GB de RAM**, com decodificação por hardware, controle do uso de imagens e ajustes de qualidade. O perfil inicial é **720p a 60 FPS, H.264 e 8 Mbps**, com opções mais leves, Full HD e configuração personalizada.

## 🎮 Uma biblioteca de jogos na sua TV

Uma interface inspirada nos serviços de filmes e séries, com destaque amplo, capas de jogos e fileiras horizontais para explorar usando o controle.

- **Início organizado:** navegação superior com acesso a Início, Minha biblioteca, Catálogo e Configurações.
- **Destaques do serviço:** carrossel com navegação manual e rotação automática.
- **Fileiras por categoria:** biblioteca, grátis para jogar, novidades, campanhas, editoras e outras seleções disponíveis nos metadados do GeForce NOW.
- **Busca e filtros:** encontre jogos pelo nome, filtre por gêneros e altere a ordenação, incluindo A–Z e Z–A.
- **Catálogo paginado:** explore a lista de jogos sem carregar todas as capas de uma vez.
- **Detalhes antes de jogar:** consulte nome, imagem, descrição, gêneros e opções de loja do título.
- **Visual de TV:** foco destacado, transições curtas e identidade própria no launcher do Android TV.

O conteúdo é atualizado a partir dos dados do serviço. Campanhas, categorias, jogos e lojas disponíveis variam conforme região, conta e provedor.

## 📚 Minha biblioteca e conexões com lojas

Encontre os jogos associados à sua conta e tenha os títulos jogados recentemente em destaque na biblioteca.

- Consulta da biblioteca e atualização de dados ao navegar pelo aplicativo.
- Identificação de propriedade conforme as informações fornecidas pela conta e pela loja.
- Opção de marcar manualmente que possui um jogo ou remover essa marcação.
- Escolha da variante de loja disponível para iniciar o título.
- Orientação para adquirir o jogo na loja oficial quando necessário.
- Área de **Conexões** para consultar e vincular lojas compatíveis, como Steam, Epic Games, Ubisoft, Xbox e Battle.net, conforme o suporte do serviço.
- Conexão pelo celular com **QR Code** ou pelo site aberto na própria TV. Código adicional é mostrado quando fornecido pelo fluxo da loja.

Marcar um jogo como possuído organiza a biblioteca; a licença continua sendo validada pela loja e pelo GeForce NOW. Algumas lojas podem restringir o login pelo navegador integrado.

## 🔐 Login pelo celular, conta lembrada

Faça a autenticação pelo site do serviço no celular, usando o QR Code ou o endereço e código apresentados na TV. Após a autorização, o aplicativo continua o acesso automaticamente.

- Restauração da conta salva ao abrir o aplicativo.
- Renovação das credenciais quando permitida pelo serviço.
- Armazenamento local de credenciais protegido com Android Keystore.
- Opção **Sair da conta** nas configurações.

O login permanece salvo enquanto a autorização estiver válida. A conta pode exigir nova autenticação se for desconectada, revogada ou expirar sem possibilidade de renovação.

## ⚡ Streaming do seu jeito

Escolha entre dois motores de reprodução:

| Motor | Experiência |
|---|---|
| **Native** | WebRTC no Android com decodificação por hardware via MediaCodec e integração com os controles do aplicativo. |
| **WebView** | Alternativa com player WebRTC em WebView, mantendo acesso às configurações e aos recursos compatíveis desse motor. |

### Perfis de qualidade

| Perfil | Resolução solicitada | FPS solicitados | Bitrate | Codec |
|---|---|---|---|---|
| **Padrão** | 1280 × 720 | 60 | 8 Mbps | H.264 |
| **Full HD** | 1920 × 1080 | 60 | 15 Mbps | H.264 |
| **Leve** | 1280 × 720 | 30 | 4 Mbps | H.264 |
| **Ultraleve** | 854 × 480 | 30 | 3 Mbps | H.264 |
| **Personalizado** | 480p, 720p ou 1080p | 30 ou 60 | Opções de 3 a 50 Mbps | H.264, H.265/HEVC ou AV1, conforme suporte |

Também é possível escolher servidor, adaptação de resolução e opções de baixa latência. O perfil Full HD verifica o suporte de decodificação H.264 anunciado pelo aparelho antes da seleção.

### Três caminhos de decodificação no motor Native

| Opção | Como funciona |
|---|---|
| **Compatível** | Usa o decodificador de hardware padrão do WebRTC. É a escolha inicial do aplicativo. |
| **Baixa latência própria** | Usa o FastDecoder para controlar o MediaCodec e entregar o vídeo ao renderizador por textura. |
| **Saída direta MediaCodec** | Usa o SurfaceDecoder para apresentar o vídeo diretamente em uma SurfaceView, evitando a etapa intermediária de renderização por textura. |

Os modos avançados priorizam decodificadores que anunciem suporte à baixa latência e solicitam esse recurso quando disponível. O resultado depende do hardware e do driver da TV. A disponibilidade de codecs depende também do motor e da negociação com o serviço.

Os valores dos perfis são os alvos solicitados. Resolução efetiva, FPS e qualidade dependem da transmissão, do plano, do servidor, da rede e da capacidade do aparelho.

## 🕹️ Controle remoto, gamepad, teclado, mouse e toque

- Navegação da interface pelo direcional e botão de confirmação.
- Suporte a gamepad durante o streaming e ajuste de zona morta.
- Teclado e mouse físicos, com reconhecimento ao conectar ou remover dispositivos.
- Opção de captura do mouse para movimento relativo em jogos compatíveis, disponível a partir do Android 8.
- Toques na imagem convertidos em movimento do ponteiro, clique e arraste.
- Teclado de escrita para enviar texto ao jogo.
- Microfone opcional, com solicitação de permissão ao aparelho. **É necessária uma entrada de áudio conectada e reconhecida pelo Android.**

### Barra rápida durante a partida

Pressione **Voltar** para abrir uma barra compacta no canto superior esquerdo:

| Ação | O que permite fazer |
|---|---|
| **Teclado** | Abrir a janela de escrita e enviar texto ao jogo. |
| **Áudio** | Controlar o som do jogo e silenciar ou reativar o microfone habilitado antes da sessão. |
| **Mouse virtual** | Mover o ponteiro com o controle da TV e enviar cliques ao streaming. |
| **Três pontos** | Ativar ou desativar estatísticas, colar texto e sair do jogo. |
| **Retornar** | Fechar a barra e continuar jogando. |

No mouse virtual, use setas ou analógico para mover, **OK/A** para clicar e **B/X** para clique direito. Pressione **Voltar** novamente para sair desse modo.

## 🔄 Fila e retomada de sessão

- Tela de preparação com a imagem do jogo escolhido.
- Posição da fila recebida do serviço, quando disponível.
- Indicação de preparação enquanto a sessão é iniciada.
- Detecção de uma sessão ativa da conta ao consultar o jogo.
- Botão **Retomar sessão**, inclusive para uma sessão aberta anteriormente em outro dispositivo.
- Botão **Fechar sessão**, abaixo da retomada, com confirmação antes de encerrar.
- Ao sair do streaming, escolha entre desconectar para retornar depois ou encerrar o jogo.

A retomada depende de o provedor ainda manter a sessão ativa. Ela não prolonga o tempo do plano nem recupera uma fila já expirada.

## 📊 Estatísticas e escolha de servidor

Acompanhe a transmissão com um painel opcional que apresenta, quando os dados estão disponíveis:

- Ping da conexão de streaming.
- FPS decodificados e resolução recebida, separados do alvo configurado.
- Bitrate recebido.
- Tempo médio recente de decodificação, em milissegundos.
- Quadros descartados, codec e identificação do decodificador.

Nas configurações de rede, consulte os servidores, compare o tempo de conexão TCP e escolha manualmente uma região ou mantenha a seleção automática. A lista destaca o menor tempo medido e mostra estimativas externas de fila quando disponíveis.

O teste TCP ajuda a comparar servidores; o ping durante a partida é medido pela conexão WebRTC. As estimativas externas de fila são aproximadas e não substituem a posição da sua sessão.

## 🪶 Pensado para aparelhos com pouca memória

O aplicativo concentra os recursos no streaming durante a partida:

- Decodificação por hardware no motor Native.
- Carregamento de capas com limites de memória e listas reutilizáveis.
- Liberação de imagens e animações do catálogo ao entrar no player.
- Pausa das consultas do catálogo durante a transmissão.
- Agrupamento de movimentos rápidos de mouse e eventos analógicos para reduzir trabalho repetido.
- Estatísticas opcionais coletadas em intervalos, fora da renderização do vídeo.
- Transições curtas e respeito à preferência de animações do Android.

## 🌍 Idioma e personalização

O idioma do sistema é identificado automaticamente. A seleção manual oferece português do Brasil, inglês, espanhol, francês, alemão, italiano, chinês, japonês, coreano, russo, árabe, hindi, turco e indonésio.

As preferências ficam salvas no aparelho. Alguns textos técnicos e fluxos externos podem aparecer em português ou inglês.

## 🚀 Comece a jogar

1. Instale o APK correspondente ao Android do aparelho: **ARMv7**, **ARM64** ou **Universal**. Há também compilação x86_64.
2. Abra o BGeforce Now TV e faça login pelo celular.
3. Aguarde o carregamento do catálogo e da biblioteca.
4. Se necessário, vincule suas lojas na área de Conexões.
5. Escolha o jogo, selecione a loja e pressione **Jogar agora** ou **Retomar sessão**.
6. Ajuste o perfil de qualidade nas configurações conforme sua TV e conexão.

**Requisitos:** Android 6.0 ou superior, conexão à internet, conta e acesso elegíveis ao GeForce NOW e licenças exigidas pelos jogos. A compatibilidade de cada função depende do Android, do hardware e do serviço. Alguns fluxos obrigatórios de publicidade em contas gratuitas ainda podem exigir o cliente oficial do provedor.

## 🛠️ Código e compilação pelo GitHub

O repositório inclui código-fonte, Gradle Wrapper e workflows do GitHub Actions para testar e gerar os APKs. O fluxo de distribuição principal verifica assinatura, integridade, pacote e arquiteturas antes de disponibilizar os arquivos.

- [Guia de compilação e instalação pelo GitHub](docs/GITHUB_PASSO_A_PASSO.md)
- [Instalação e atualização do aplicativo](docs/INSTALACAO.md)
- [Controles e recursos durante a partida](docs/OTIMIZACAO_E_TECLADO.md)
- [Perfis de qualidade e interface de TV](docs/NOVO_LAYOUT_E_1080P.md)
- [Verificações realizadas](docs/VERIFICACAO.md)
- [Licença do projeto](LICENSE) e [créditos de terceiros](THIRD_PARTY_NOTICES.md)

---

**BGeforce Now TV é um projeto independente da comunidade.** Não é um aplicativo oficial da NVIDIA nem possui afiliação com ela. GeForce NOW, NVIDIA, nomes de lojas, jogos e demais marcas pertencem aos seus respectivos titulares.
