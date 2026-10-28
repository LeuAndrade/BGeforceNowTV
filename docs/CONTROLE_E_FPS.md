> Atualização 0.14: interface de TV renovada, perfil Full HD opcional, fechamento explícito nos detalhes e recuperação limitada de GET 500/peerRemoved/inicialização sem quadro. As otimizações dos controles da 0.13 permanecem. Leia NOVO_LAYOUT_E_1080P.md e VERIFICACAO.md para o comportamento atual e os testes. As seções abaixo registram também revisões anteriores.

# Controle e FPS — revisão 0.13

O relato de queda de FPS ao apertar o controle motivou a revisão do caminho de entrada. Foram encontradas e corrigidas redundâncias de agendamento/consulta e alocação. Isso reduz trabalho associado aos comandos sem diminuir resolução, bitrate ou FPS. Não foi identificado por um trace da TV qual componente causa cada queda física de FPS.

## Mudanças

- Autorrepetições do mesmo botão atualizam apenas o estado necessário: não consultam InputDevice nem rearmam o timer repetidamente. A primeira mudança de pressionar/soltar é enviada imediatamente.
- Estado atual e último estado enviado continuam distintos. Combinações rápidas, solturas e transições de neutro não são agrupadas como se fossem amostras analógicas.
- O timer do controle mantém um único prazo pendente; eventos que chegam antes dele usam a posição analógica mais recente. Amostras idênticas não voltam ao agendador.
- Keepalives ativos de 100 ms e neutros de 1 segundo permanecem. A abertura/fechamento da barra cancela/reinicia o timer e solta entradas, evitando estado preso.
- Direcional/OK e fontes de teclado compatíveis de um controle não são enviados como setas de PC duplicadas. Teclado de PC, inclusive letras de dispositivos híbridos no mouse virtual, mantém seu funcionamento.
- O codificador de gamepad escreve o envelope v3 no mesmo array do estado, evitando o array intermediário e sua cópia. Não há reutilização mutável de pacotes já enfileirados. Teclado/mouse mantêm seus formatos existentes.

## Evidência

No teste com código real da atividade e 10 mil eventos de botão mantido pressionado, a 0.12 fez 10 mil agendamentos e 10 mil consultas de dispositivo. A 0.13 fez um agendamento e nenhuma consulta. Nos dois casos, o mesmo estado de botão gerou um pacote nesse ensaio com relógio controlado; não se está alegando que a versão anterior enviava dez mil pacotes ou acumulava dez mil callbacks simultâneos.

No ensaio do codificador em Corretto 17.0.21/Linux x86_64, foram 128 → 72 bytes alocados por pacote (43,75% a menos), com 200 mil chamadas de aquecimento e 250 mil medidas. É uma medição de JVM; não representa RAM total, ART da TV, latência fim a fim ou FPS de vídeo. Fonte do ensaio em validation/controller-allocation.

Vetores fixos verificam todos os bytes do protocolo v2/v3, incluindo timestamps com ordens de bytes diferentes. Há testes de pressionar/soltar durante amostras analógicas, botões simultâneos, repetição após overlay, prazo de keepalive, cancelamento e fontes de teclado/direcional.

## Preservado

Padrão Native/Compatível, 1280×720, 60 FPS, H.264, 8 Mbps; resolução/FPS/bitrate/codec personalizados; decodificação por hardware; microfone/áudio; teclado/mouse/toque; barra compacta; estatísticas; retomada de sessão e login salvo. A UI do catálogo e o renderer não foram redesenhados.

O painel continua distinguindo meta de FPS e FPS decodificado. Não força o número 60 no campo medido. Rede, servidor, driver, carga e aquecimento do aparelho ainda podem afetar a taxa. Não houve uma partida autenticada ou medição de FPS sustentado na TV física nesta revisão.

Resultados exatos de testes, instalação e hashes em VERIFICACAO.md. Referência oficial para eventos repetidos e fontes de controles: https://developer.android.com/games/sdk/game-controller/controller-input.
