> Atualização 0.15: veja [FILA_E_INICIO.md](FILA_E_INICIO.md) para a correção de fila e espera de SDP e [VERIFICACAO.md](VERIFICACAO.md) para os testes atuais. O texto abaixo documenta a revisão anterior.

> Atualização 0.14: interface de TV renovada, perfil Full HD opcional, fechamento explícito nos detalhes e recuperação limitada de GET 500/peerRemoved/inicialização sem quadro. As otimizações dos controles da 0.13 permanecem. Leia NOVO_LAYOUT_E_1080P.md e VERIFICACAO.md para o comportamento atual e os testes. As seções abaixo registram também revisões anteriores.

# Nota da revisão 0.13

A 0.13 não altera autenticação, criação/RESUME REST ou endpoints. Os ajustes são no tratamento e na codificação das entradas do controle.

# Revisão 0.12

Nenhum endpoint, corpo REST de criação/RESUME, renovação de conta ou política de repetição de mutações foi alterado. A revisão corrige a sinalização WSS da retomada, incluindo identidade do peer, protocolo inicial e falhas transitórias antes do SDP. Consulte RETOMADA_DA_SESSAO.md.

# Nota da revisão 0.11

Esta revisão trata entrega/assinatura dos APKs e estabilidade das estatísticas. Mantém a correção anterior de criação/autenticação/retomada de sessão; não repetiu requisições mutantes nem mudou endpoints para tentar contornar erros do servidor. Um erro HTTP 500 atual do serviço ainda exige a resposta concreta para atribuir causa. Veja INSTALACAO.md se o Android recusar o pacote antes de abrir o app.

Atualização 0.10: a criação de sessão que funcionou na 0.9 foi preservada. A retomada consulta uma sessão existente e usa RESUME PUT sem criar outra; os testes cobrem 401, rejeição de rota, 500 e leituras após o claim. Não há mais botão de diagnóstico nas configurações.

Atualização 0.9: o usuário confirmou a 0.8 funcionando; esta revisão reduz trabalho auxiliar de streaming e preserva o contrato de sessão descrito abaixo.

# Diagnóstico do erro enviado e atualização 0.5

**Registro histórico.** Após a 0.5, o usuário confirmou que o streaming iniciou. A entrega atual é a 0.8 principal, com padrão 720p60 e correção da NetworkOnMainThreadException ao abrir a partida; veja OTIMIZACAO_E_TECLADO.md e VERIFICACAO.md. As etapas abaixo documentam a investigação anterior e não indicam que o usuário continua bloqueado no HTTP 500. A 0.8 mantém essas correções de sessão.

## O que a foto comprova

A captura enviada mostra a versão 0.4, Genshin Impact, renderer native, perfil 1280×720/60/H.264. A falha está no `POST /v2/session` para `prod.cloudmatchbeta.nvidiagrid.net`, com HTTP 500, `statusCode=4`, `INTERNAL_ERROR_STATUS 8A8C0000`, `unifiedErrorCode=0` e `serverId=NP-MIA-03`.

O serviço ainda não criou uma sessão utilizável. Portanto, nesse ponto não começou a decodificação de vídeo. Reduzir bitrate, alterar MediaCodec ou anunciar uma correção de FPS não demonstra correção deste erro.

**Esse retorno é genérico. Ele não identifica sozinho qual campo foi rejeitado e não comprova uma causa única.**

## Divergências encontradas e corrigidas no pedido

1. Na 0.4, servidores NVIDIA recebiam sempre a identidade BROWSER/WEBRTC/ANDROID/PHONE, inclusive com renderer native. Agora native utiliza o perfil de alocação NATIVE/NVIDIA-CLASSIC/WINDOWS/DESKTOP, com `clientPlatformName=windows` e descritor completo de monitor. Cabeçalhos e corpo vêm do mesmo perfil. O WebView na NVIDIA mantém o perfil de navegador, e os parceiros mantêm seu perfil nativo.
2. O campo `userAge`, presente no contrato e no pedido da referência, não era enviado. Agora o pedido contém uma idade numérica válida. O aplicativo usa idade/data de nascimento já disponível no perfil da conta ou nas claims do id_token. Se não houver esse dado utilizável, pede a confirmação da idade real antes do POST e a guarda junto da sessão, no armazenamento cifrado da conta. Não assume a idade adulta fixa usada na referência. A confirmação é isolada por conta, não substitui dados de idade fornecidos pela NVIDIA e é solicitada novamente se ficar antiga.
3. O perfil de alocação é mantido na consulta/encerramento mesmo quando o serviço fornece outro hostname para controlar a sessão. Não muda inadvertidamente de navegador para nativo ao trocar de hostname.
4. Ao iniciar um jogo gratuito ainda fora da biblioteca, o app confirma a inclusão da variante no GFN antes de criar a sessão. O campo accountLinked só reflete essa inclusão depois da confirmação do servidor; jogos pagos não são marcados automaticamente. A mutação usa a assinatura language/variantId conferida na referência.
5. A mensagem agora inclui o perfil efetivamente usado e o appId público de lançamento, além de outros códigos do servidor quando disponíveis. Não inclui idade nem data de nascimento no resumo técnico.

A classe do pedido nativo continua solicitando **WebRTC** (`streamerVersion=1`, `sdkVersion=1.0`, `enhancedStreamMode=1`, metadado `GSStreamerType=WebRTC`). A mudança de identidade não ativa o transporte NVST proprietário e não troca o player Android por um site. Resolução, FPS e codec selecionados permanecem no perfil solicitado.

## Referência conferida

Foi revisada a implementação Android do OpenNOW na revisão `1bea55a39c23d66780b9aedd85a5d967173d58d6`, em especial `GfnApi.kt` (identidades e `buildSessionRequestBody`) e `Models.kt`. A seleção de ID numérico da variante também foi conferida e segue a referência; não foi alterada arbitrariamente.

Fonte: https://github.com/OpenCloudGaming/OpenNOW/tree/1bea55a39c23d66780b9aedd85a5d967173d58d6/android/app/src/main/java/com/opencloudgaming/opennow

A página específica da NVIDIA sobre Genshin lista PC Windows, macOS, Chromebook, iOS/iPadOS Safari e dispositivos Android móveis. Android TV não consta nessa lista. Isso é uma consideração para a validação desse título, e **não prova que a restrição seja a causa do 500 da imagem**. O serviço continua decidindo elegibilidade por jogo, plataforma, região, assinatura e conta.

Fonte: https://nvidia.custhelp.com/app/answers/detail/a_id/5300/~/genshin-impact-on-geforce-now

## Como instalar e testar

1. Instale o APK Teste 0.5 por cima do **BGeforce Now TV Teste 0.4** recebido nesta conversa. Pacote e certificado foram mantidos; não é necessário desinstalar e a conta/configurações existentes devem ser preservadas.
2. Ao jogar, se a conta não fornecer uma idade utilizável, confirme a sua idade real na janela. O aplicativo não pede senha nessa confirmação.
3. Tente iniciar o jogo em native. Para separar um possível problema específico de Genshin da abertura geral de sessões, teste também outro título da sua biblioteca.
4. Se ocorrer erro, envie a mensagem inteira/foto e indique o jogo e a loja. A versão 0.5 imprime o perfil e o appId usados, o que permite comparar o pedido com a resposta real.
5. Se você usa uma compilação própria do GitHub, preserve a sua chave de assinatura. Os APKs fornecidos aqui não substituem uma instalação assinada com outra chave.

## O que não foi comprovado

As divergências do código foram corrigidas e testadas com respostas HTTP controladas. **Não houve uma nova partida autenticada no GFN depois das correções. Não se afirma que o erro 8A8C0000 da conta do usuário desapareceu, nem que todos os jogos agora iniciam.**

Publicidade obrigatória ainda não tem player implementado. A descoberta de todos os parceiros e fluxos de lojas depende de validação real. Não foram medidos consumo de RAM, tempo de decodificação ou FPS sustentado na TV de 1 GB.
