# GitHub e APK principal — versão 0.15

O padrão continua **720p/60 FPS**. A 0.15 corrige a leitura da fila e a transição para o servidor atribuído, limita a espera de SDP e preserva a interface e o Full HD opcional da 0.14. Preserva as otimizações dos controles, pacote e assinatura. Instale por cima da versão principal atual para manter os dados. Veja NOVO_LAYOUT_E_1080P.md para usar o perfil e VERIFICACAO.md para os resultados e limites.

Se já tem o repositório e os secrets da assinatura principal 0.7, atualize os arquivos no mesmo repositório e rode novamente o workflow principal. A chave é a mesma; não gere outra. O pacote antigo .preview da 0.6 é separado.

## 1. Criar ou atualizar seu repositório

1. Faça login em [github.com](https://github.com).
2. Se ainda não tem o repositório, abra [github.com/new](https://github.com/new), escreva BGeforceNowTV, escolha Public ou Private, marque Add a README file e clique Create repository. Não use Fork/Import repository.
3. Se já criou o repositório deste app, use o mesmo.
4. Baixe BGeforceNowTV-codigo.zip desta entrega.
5. No repositório, abra Code → Codespaces → Create codespace on main, ou abra o Codespace existente. Confira as cotas do seu plano.
6. No painel de arquivos do editor, use Upload… na raiz e envie o ZIP. Renomeie para BGeforceNowTV-codigo.zip se o download tiver um sufixo como (1).
7. Abra Terminal → New Terminal e cole:

```bash
unzip -oq BGeforceNowTV-codigo.zip -d /tmp/bgfn-import
cp -a /tmp/bgfn-import/BGeforceNowTV/. .
rm BGeforceNowTV-codigo.zip
chmod +x gradlew
git add .
git commit -m "Atualizar BGeforce Now TV para 0.15"
git push
```

8. Volte ao GitHub e confira que app, gradle, .github, build.gradle e settings.gradle estão na raiz. Enviar um ZIP fechado pelo Upload files do site não descompacta o projeto. O procedimento acima preserva .github.

## 2. Conferir testes e build de desenvolvimento

Abra Actions → Gerar APK. Habilite workflows se for solicitado, aguarde o ✓ verde e abra a execução. Em Artifacts, BGeforce-Now-TV-Dev-APKs contém o app de desenvolvimento, com pacote separado `.main.dev`. Relatorios contém os resultados. Se não houver execução, use Run workflow → main.

Um APK unsigned não pode ser instalado antes da assinatura. Para o app principal, configure uma chave fixa na etapa seguinte.

## 3. Usar a assinatura principal entregue separadamente

O arquivo **BGeforceNowTV-assinatura-privada.zip** contém a chave principal e assinatura.json com alias/senha. Guarde-o em local privado; não publique esse ZIP nem seu conteúdo no GitHub. Ele não está no ZIP de código. Usar a mesma chave permite atualizar os APKs principais 0.7/0.8/0.9/0.10/0.11/0.12/0.15 recebidos nesta conversa.

1. No terminal do Codespace, execute `mkdir -p /tmp/bgfn-assinatura`.
2. No editor, use File → Open Folder e abra `/tmp/bgfn-assinatura`.
3. Use Upload… nessa pasta e envie o ZIP de assinatura privada. Assim ele fica fora do repositório.
4. No terminal dessa pasta, execute:

```bash
unzip -oq BGeforceNowTV-assinatura-privada.zip
base64 -w 0 BGeforceNowTV-main.jks
```

5. Copie todo o texto base64. Ele representa a chave privada. Na página do repositório, vá a Settings → Secrets and variables → Actions → New repository secret. Crie `BGFN_KEYSTORE_B64` com esse texto.
6. Abra assinatura.json no editor. Copie apenas o valor de `store_password`, sem aspas, e crie o secret `BGFN_STORE_PASSWORD` com ele. O alias é `bgfn` e a senha da chave é a mesma; o workflow já usa esses valores.
7. Não coloque senha/base64 em commit, issue, README ou conversa pública.
8. Vá a Actions → APK principal com sua assinatura → Run workflow → main → Run workflow.
9. Espere ✓ verde. Em Artifacts, baixe BGeforce-Now-TV-principal e extraia. Os APKs já estão assinados e passaram por scripts/verify-apks.py. Também há SHA256SUMS.txt, APKS-VERIFICADOS.json e LEIA-ANTES-DE-INSTALAR.txt. O workflow falha se encontrar APK sem assinatura, íntegro mas dividido/incompleto, pacote inesperado ou arquitetura inconsistente. Nesta revisão a minificação R8 está desligada por compatibilidade; as otimizações de streaming permanecem no código.
10. Depois de guardar o backup privado, remova os arquivos temporários de assinatura do Codespace pelo painel de arquivos. Pare o Codespace em [github.com/codespaces](https://github.com/codespaces) ao terminar.

## 4. Escolher o APK

| Arquivo no artifact | Sistema Android |
|---|---|
| BGFN-TV-15-ARMv7.apk | ARM 32 bits |
| BGFN-TV-15-ARM64.apk | ARM 64 bits |
| BGFN-TV-15-Universal.apk | Quando não souber; arquivo maior |
| BGFN-TV-15-x86_64.apk | Intel/AMD 64 bits, inclusive emuladores |

Os três APKs ARMv7, ARM64 e Universal são gerados juntos; o artifact também contém x86_64 para testes/emuladores. Transfira o APK escolhido à TV, abra no gerenciador e autorize a instalação por esse gerenciador se o Android solicitar. Abra o app 0.15 e use Native → Compatível → Padrão 720p60. Em instalação nova, faça login no celular pelo QR; ao atualizar a 0.7, 0.8, 0.9, 0.10, 0.11, 0.12 ou 0.13, a conta permanece salva.

Nesta revisão, os APKs desta entrega e os verificados no GitHub usam os nomes da tabela. O número acompanha o versionCode. A escolha é pelo Android instalado, não apenas pela capacidade do processador. Extraia o artifact baixado: o ZIP de download não é um APK. Nunca instale um arquivo terminado em unsigned.apk. Veja INSTALACAO.md se o Android informar erro de análise do pacote.

## 5. Próximas atualizações

Atualize os arquivos no mesmo repositório com o novo ZIP. Preserve os secrets e a chave principal. Aumente versionCode para cada nova entrega; esta usa 14 / 0.15.0. Rode novamente APK principal com sua assinatura. Não gere uma chave nova a cada build.

Se você já usa uma compilação própria anterior com outro pacote/chave, preserve sua chave. Para manter aquele identificador, adicione em gradle.properties uma linha como `bgfnApplicationId=com.bgeforce.tv.preview`, usando exatamente o pacote da sua instalação. Esse ajuste não resolve certificado diferente: pacote e chave precisam corresponder. O pacote .preview da 0.6 e o .main da 0.7/0.8/0.9/0.10/0.11/0.12/0.15 não compartilham dados de conta.

Você também pode gerar uma chave sua com keytool em vez da entregue, usando alias bgfn e a mesma senha para chave/keystore. Ela não atualiza uma instalação que use a assinatura entregue; escolha seu próprio pacote para instalar em paralelo. Não envie chaves com o código.

Para instalação, leia INSTALACAO.md. Para erros de partida, leia FECHAMENTO_STREAMING.md. Para limites e resultados, ESTADO_REAL.md e VERIFICACAO.md. Testes com respostas simuladas não substituem partida numa TV.

Referências oficiais: [criar repositório](https://docs.github.com/pt/repositories/creating-and-managing-repositories/creating-a-new-repository), [baixar artifacts](https://docs.github.com/actions/managing-workflow-runs/downloading-workflow-artifacts) e [secrets](https://docs.github.com/en/actions/concepts/security/secrets).

Para a correção de Retomar, consulte RETOMADA_DA_SESSAO.md. Não é necessário criar outro repositório, mudar o pacote ou gerar outra chave para esta atualização.

Para as alterações dos controles, consulte CONTROLE_E_FPS.md. Use o mesmo repositório e os mesmos secrets da assinatura principal.

A navegação da 0.15 fica no topo. Full HD é uma escolha explícita em Configurações → Perfil de desempenho, após conferência de hardware. Uma sessão já aberta mantém sua configuração até ser encerrada/iniciada outra.
