# Instalar BGeforce Now TV 0.15

Para um Android ARM de 32 bits, use **BGFN-TV-15-ARMv7.apk**. O tamanho e o SHA-256 de cada APK desta revisão estão em APKS-VERIFICADOS.json e no ZIP de instalação. Builds feitos no GitHub podem ter tamanho/hash diferente; compare com APKS-VERIFICADOS.json e SHA256SUMS.txt do próprio artifact. O ZIP de instalação contém esse mesmo arquivo, um texto de orientação e seu SHA-256.

## Passo a passo

1. Baixe **BGFN-TV-15-ARMv7-INSTALAR.zip** no celular/computador e aguarde o download concluir.
2. Extraia o ZIP. A pasta deve conter BGFN-TV-15-ARMv7.apk; não renomeie o ZIP para APK.
3. Transfira o APK extraído para a TV. Se o gerenciador da TV descompacta ZIP, também pode transferir e extrair o ZIP lá.
4. Confira o tamanho do APK após a transferência com o manifesto desta revisão. Para conferir o conteúdo com precisão, compare o SHA-256 com SHA256-APK.txt. O tamanho sozinho não comprova integridade.
5. Abra o arquivo .apk no gerenciador da TV. Autorize instalações por esse gerenciador se o Android solicitar.
6. Escolha **Atualizar**, mantendo o app principal instalado. As versões principais 0.7–0.15 desta conversa usam com.bgeforce.tv.main e a mesma chave. Não é necessário apagar dados nem desinstalar para essa atualização.
7. Abra o aplicativo. O padrão continua Native → Compatível → 720p60 → H.264 → 8 Mbps. Configurações que você já personalizou permanecem salvas.

Também há um APK Universal, maior, para sistemas cuja arquitetura não é conhecida. Uma TV com processador de 64 bits pode ter Android de 32 bits; o APK deve corresponder ao sistema. O Universal inclui ARMv7, ARM64 e x86_64; não melhora a decodificação por conter mais arquiteturas.

## Versões e atualização

| APK | Escolha |
|---|---|
| BGFN-TV-15-ARMv7.apk | Android ARM de 32 bits |
| BGFN-TV-15-ARM64.apk | Android ARM de 64 bits |
| BGFN-TV-15-Universal.apk | Inclui ARMv7, ARM64 e x86_64; use quando não souber a arquitetura |

Cada uma dessas três variantes também tem um ZIP terminado em -INSTALAR.zip. Todas usam a mesma revisão, pacote e certificado. Instale somente uma variante. Para trocar de ARMv7 para Universal no mesmo aparelho compatível, instale por cima, sem apagar dados.

A revisão 0.15 usa o mesmo pacote e certificado da 0.13. O ensaio de atualização 0.13 → 0.14 pertence à entrega anterior; não foi repetido com a 0.15. Os testes desta revisão estão em VERIFICACAO.md. A otimização dos controles da 0.13 foi mantida; Full HD 1080p60 é opcional e não muda automaticamente o padrão 720p60.

## Se a TV ainda recusar

| Conferência | Ação |
|---|---|
| Arquivo baixado/transferido incompleto ou alterado | Baixe o ZIP novamente, extraia e compare o SHA-256 do APK na TV. Não use o arquivo interrompido anterior. |
| É um ZIP de artifact, fonte TXT, AAB, página HTML ou APK unsigned | Extraia o artifact e selecione o APK assinado. Trocar a extensão não converte o arquivo. |
| APK ARM64 em Android ARM de 32 bits | Use ARMv7; na dúvida sobre o sistema, tente o Universal. |
| Android anterior ao 6.0/API 23 | Esta versão não suporta esse sistema. A TV Android 10 identificada antes atende ao requisito. |
| Outro app com mesmo pacote e assinatura diferente | Preserve sua chave original ao compilar. Uma chave nova não atualiza aquele app. O APK principal desta conversa mantém sua chave desde a 0.7. |
| Arquivo correto ainda rejeitado | O texto genérico da TV não revela o código do PackageManager. Anote a versão/modelo do Android e a mensagem completa; um teste via adb install -r pode revelar o código concreto. Não apague o login para tentar corrigir análise de pacote. |

No computador com Android Platform Tools e a TV conectada/autorizada, o comando opcional é:

```bash
adb install -r BGFN-TV-15-ARMv7.apk
```

Essa revisão não garante instalação em todo firmware de fabricante. Os ensaios de instalação não medem FPS de jogo ou desempenho do decoder da TV.

Ferramentas oficiais usadas na conferência: [apksigner](https://developer.android.com/tools/apksigner), [zipalign](https://developer.android.com/tools/zipalign) e PackageManager do Android 10.

A 0.15 conserva a assinatura e corrige o caminho dos comandos do controle. Instale por cima da versão principal 0.11/0.12, mantendo os dados. A correção de retomada da 0.12 está incluída. Veja VERIFICACAO.md para o teste de atualização desta entrega.

A interface passa a ter menu superior. Para Full HD, leia NOVO_LAYOUT_E_1080P.md. As novas opções de Retomar/Fechar ficam nos detalhes do jogo quando uma sessão é identificada.
