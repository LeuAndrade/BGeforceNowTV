# Codificação dos comandos: alocação em JVM

Este ensaio verifica um pacote fixo do protocolo e mede bytes alocados pela thread Java ao codificar comandos. Não é um benchmark de Android, de decodificação, de qualidade de imagem, de latência fim a fim ou de FPS do streaming.

Com um JDK 17 completo, na raiz do projeto:

```bash
javac -d /tmp/bgfn-allocation app/src/main/java/com/bgeforce/tv/InputEncoder.java validation/controller-allocation/Allocation.java
java -cp /tmp/bgfn-allocation com.bgeforce.tv.Allocation
```

Na comparação desta revisão, Corretto 17.0.21 em Linux x86_64, 200 mil iterações de aquecimento e 250 mil medidas:

- Codificador 0.12: 32.000.000 bytes, 128 bytes por pacote.
- Codificador 0.13: 18.000.000 bytes, 72 bytes por pacote.
- Redução de 43,75% neste ensaio, mantendo o vetor de bytes do protocolo.

A 0.13 escreve o envelope e o estado do controle num único array, evitando o array intermediário e sua cópia. Cada pacote continua independente; não há reutilização mutável que possa sobrescrever um pressionar/soltar já enfileirado. O resultado de alocação da JVM não garante o mesmo número no ART da TV nem um ganho específico de FPS.
