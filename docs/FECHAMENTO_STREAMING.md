> Atualização 0.14: interface de TV renovada, perfil Full HD opcional, fechamento explícito nos detalhes e recuperação limitada de GET 500/peerRemoved/inicialização sem quadro. As otimizações dos controles da 0.13 permanecem. Leia NOVO_LAYOUT_E_1080P.md e VERIFICACAO.md para o comportamento atual e os testes. As seções abaixo registram também revisões anteriores.

# Nota da revisão 0.13

A 0.13 mantém as correções de retomada e limpeza de rede; otimiza o caminho dos comandos sem trocar o decoder ou reduzir qualidade.

# Revisão 0.12

A falha de retomada mostrada na nova foto ocorre na sinalização, não na instalação nem num fechamento Java comprovado. A 0.12 separa a primeira conexão da reconexão, conserva o peer entre tentativas e inclui a classe da exceção na mensagem. Mantém as correções anteriores de limpeza de rede fora da UI. Veja RETOMADA_DA_SESSAO.md e VERIFICACAO.md.

# Revisão 0.11

Erro de análise acontece antes de o player iniciar: siga INSTALACAO.md. Foram preservadas as correções anteriores de fechamento. Uma falha opcional da coleta de estatísticas agora é isolada do transporte de mídia, e números inválidos não atravessam o callback JNI. O decoder Compatível, biblioteca nativa e criação/autenticação de sessão permanecem iguais à 0.10.

Nota da 0.10: a correção de HTTPS na UI descrita abaixo permanece. O diagnóstico foi removido das configurações e não abre automaticamente. Em caso de erro operacional, use o texto da própria mensagem de erro. O usuário confirmou a 0.9 funcionando.

# Fechamento ao tocar em Jogar agora — 0.8

A correção abaixo foi introduzida na 0.8 e permanece na 0.9. O usuário confirmou o funcionamento da 0.8; esta revisão preserva o caminho de limpeza e o decoder padrão.

## Evidência do aparelho

A foto enviada pelo usuário mostra BGeforce Now TV 0.7.0, Android API 29, SDMC IZY01, ABI armeabi-v7a/armeabi, perfil solicitado native/compatible 1280×720/60 H.264. A última etapa registrada é sessão, antes da inicialização do player.

O stack mostra RuntimeException em ActivityThread.callActivityOnStop com causa NetworkOnMainThreadException, StrictMode$AndroidBlockGuardPolicy.onNetwork e ConscryptFileDescriptorSocket.shutdownAndFreeSslNative. O Android detectou operação de rede na thread principal durante o fechamento de uma conexão TLS.

## Caminho corrigido

Ao abrir StreamActivity, Android para a atividade do catálogo. MainActivity.onStop chamava Dispatcher.cancelAll, Images.close e ConnectionPool.evictAll diretamente na UI. Cancelar ou fechar uma conexão HTTPS pode fazer I/O de rede, mesmo sem uma requisição nova. Images.close também fechava o cache/pool no callback do primeiro quadro e no encerramento da tela.

NetworkCleanup agora captura as chamadas antigas e agenda cancelamento/liberação/interrupção de workers em uma thread de fundo. As listas e bitmaps saem da memória da UI imediatamente. A mesma política cobre onDestroy, logout, trim/close de imagens e encerramento da sinalização. O worker é criado sob demanda e se encerra após 15 segundos ocioso. A política StrictMode não é relaxada.

Capturar as chamadas antes de agendar evita que um cancelAll atrasado cancele o novo carregamento da biblioteca ou uma nova autenticação. O pool só libera conexões ociosas. Cada diretório de imagens tem uma instância de Cache por processo, compartilhada pelos loaders; fechar um loader não fecha o cache de outra tela. Não há Context/Activity guardada nesse registro de caches.

## Reprodução e regressão

Foi executado o teste clickingPlayStopsCatalogWithoutClosingTlsOnUiThread contra a rotina onStop da 0.7: falhou com NetworkOnMainThreadException em MainActivity.onStop. O socket de teste lança a exceção se fechado na thread chamadora que representa a UI.

Com a rotina corrigida, o mesmo teste passa e confirma que onStop retorna enquanto o fechamento continua bloqueado em segundo plano. Sete outros casos verificam destruição da atividade, fechamento e trim das imagens, preservação das chamadas novas, continuidade da limpeza após falha isolada, reutilização do cache e fechamento da sinalização fora da UI.

Isso é uma reprodução controlada do caminho Java com um socket protegido, não execução real de Conscrypt/ActivityThread numa TV. O relatório recebido identifica a falha concreta; ainda é necessário observar a partida após a atualização.

## Instalação

Escolha o APK 0.10 ARMv7 para a TV mostrada no diagnóstico e instale por cima da 0.7, 0.8 ou 0.9 principal recebida nesta conversa. Pacote e certificado são iguais; conta/configurações permanecem no mesmo armazenamento. Não é preciso desinstalar nem limpar os dados.

Use Native → Compatível → Padrão 720p60. O alvo continua 60 FPS; servidor/hardware determinam o resultado real. Se houver novo erro operacional, use Copiar erro na própria janela e informe até qual etapa chegou. Um registro legado da 0.7 pode aparecer ao primeiro abrir a atualização; a mensagem o identifica como execução anterior quando a versão não foi gravada no formato antigo.

Referências primárias: [NetworkOnMainThreadException](https://developer.android.com/reference/android/os/NetworkOnMainThreadException) e código da dependência OkHttp 4.12.0. Leia VERIFICACAO.md para a compilação, testes e assinaturas finais.
