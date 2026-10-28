# Ensaio opcional de instalação/atualização e controles

Este harness usa Instrumentation do Android, sem JUnit externo. Não faz parte do aplicativo principal. Compile-o com a mesma chave do release alvo. Ele usa mídia falsa e bloqueia as tarefas de criação de sessão somente dentro do processo de teste. Não há hook de teste no APK principal, acesso à conta ou partida de benchmark.

Requer Java 17, SDK 35/build-tools 35.0.0 e Android 6+. Informe ANDROID_SDK_ROOT, JAVA_HOME, BGFN_KEYSTORE, BGFN_STORE_PASSWORD, BGFN_KEY_PASSWORD e BGFN_KEY_ALIAS no ambiente. Não escreva as senhas em commit.

```bash
python3 validation/android-smoke/build.py --output /tmp/bgfn-smoke
adb install -r /tmp/bgfn-smoke/BGFN-validation.apk
```

Em um emulador/dispositivo reservado a testes, com o APK principal 0.13 instalado, grave a sentinela de preferências:

```bash
adb shell am instrument -w -r -e mode seed com.bgeforce.tv.validation/com.bgeforce.tv.validation.Smoke
adb install -r BGFN-TV-14-Universal.apk
adb shell am instrument -w -r com.bgeforce.tv.validation/com.bgeforce.tv.validation.Smoke
```

A segunda execução acrescenta quatro verificações da identidade de sinalização (primeira conexão, reconexão com peer estável e mudança de endpoint) e confere a sentinela preservada, defaults 720p60, barra Voltar, geometria compacta, mouse virtual/cliques, retorno ao jogo, teclado e distinção entre meta 60 e leitura 40 nas estatísticas. Verifique INSTRUMENTATION_RESULT: checks e result; não confunda o exit code do comando adb com a aprovação do teste. Falha aparece em INSTRUMENTATION_RESULT: failure.

O teste escreve apenas preferências de ensaio no dispositivo de teste. Não é um teste de login/credenciais preservadas, transferência real de sessão, hardware MediaCodec, periféricos físicos ou FPS sustentado. Não rode na TV de uso diário para medir desempenho.

A 0.13 acrescenta verificações com KeyEvent reais: mil autorrepetições de botão mantêm o prazo e os dois pacotes de pressionar/soltar, e o OK do direcional é consumido pelo gameplay. A mídia permanece simulada; não se mede FPS físico nesse teste.

## Ensaio visual da 0.14

O APK de testes também contém TvUiSmoke, separado do APK principal. Ele bloqueia o executor de catálogo antes do onCreate, usa metadados locais de ensaio e intercepta as imagens para ler arquivos locais. Não faz login nem chama APIs de sessão/loja. O target deve ser um dispositivo/emulador reservado a testes, com a mesma assinatura.

```bash
python3 validation/android-smoke/fetch-art.py --output /tmp/bgfn-ui-art
export BGFN_UI_TEST_ASSETS=/tmp/bgfn-ui-art
python3 validation/android-smoke/build.py --output /tmp/bgfn-smoke
adb install -r /tmp/bgfn-smoke/BGFN-validation.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell am instrument -w -r com.bgeforce.tv.validation/com.bgeforce.tv.validation.TvUiSmoke
```

As capas pertencem aos titulares dos jogos e não integram o código ou o APK principal. O ensaio confere menu superior/busca, remoção da fileira duplicada, retorno do destaque após reciclagem, Retomar/Fechar, cancelamento do encerramento e geometria/visibilidade das abas. As capturas ficam em /sdcard/Android/data/com.bgeforce.tv.main/files/ui-*.png apenas no dispositivo de teste. No Android 10+, a Instrumentation assume permissão de injeção para seus eventos; o aplicativo principal não possui essa permissão. Confira result/failure, não apenas o exit code do adb. Não use esses ensaios como medida de FPS, periféricos físicos ou conta preservada.

A sentinela da 0.14 usa v13-to-v14-same-key: execute o modo seed com a 0.13 instalada, atualize para BGFN-TV-14-Universal.apk e execute Smoke. Leia validation/RESULTADOS_ANDROID10.txt para o ensaio desta entrega.
