# Verificação da versão 0.15.0

## Verificação e limites

258 testes JUnit passaram (242 anteriores + 16 novos), incluindo trocas de rota com HTTP controlado e sinalização WebSocket sobre TLS validado. 59 verificações JavaScript passaram. Lint: zero erros e 31 avisos. APKs release ARMv7, ARM64, x86_64 e universal têm pacote/assinatura principal preservados e passam no verificador de assinatura v1/v2, alinhamento, manifesto, DEX, ABIs e integridade ZIP.

A regressão da leitura/prontidão foi reproduzida na fonte 0.14 com o novo teste de contrato e apenas a classe de filtro adicionados: 8 testes, 4 falhas esperadas; os mesmos 8 passam na 0.15. O teste de socket silencioso usa um prazo reduzido somente no construtor de ensaio, para verificar o caminho real de timeout sem aguardar 12 s em cada conexão.

Não houve partida autenticada nem acesso remoto à TV do usuário nesta revisão. As 25 verificações Android de player/entrada e 20 de interface registradas na pasta validation são históricas da 0.14, não execuções da 0.15. Não há benchmark físico de decode/FPS nem garantia de ausência de toda falha do servidor.

Referência técnica consultada: protocolo de sessões/sinalização do projeto OpenNOW e Opennow-web (fontes preservadas da pesquisa anterior). As correções de fila, confirmação de aumento e gestão da passagem de endpoint neste projeto são próprias.
