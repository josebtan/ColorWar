# ColorWar (Paper, Java)

Minijuego tipo Paper.io en Minecraft: sal de tu territorio dejando estela de polvo de concreto,
vuelve para cerrar el bucle y conquistar todo lo encerrado. Si pisan tu estela, mueres.

## Compilar
    mvn clean package        # el .jar queda en target/ColorWar-1.0.0.jar

En pom.xml cambia `paper.version` y `java.version` a los de tu servidor
(1.21.x -> Java 21; 26.x -> Java 25). Copia el jar a /plugins.

## Crear una arena
1. Construye un suelo plano (SMOOTH_STONE) de entre 10x10 y 200x200 con paredes.
2. Párate en una esquina:   /cw create mi_arena   y   /cw pos1 mi_arena
3. Párate en la esquina opuesta:   /cw pos2 mi_arena
4. Opcional: /cw setlobby mi_arena | /cw setmainlobby
5. Modo y spawns:  /cw set mi_arena mode team|solo
                   /cw set mi_arena spawnmode random|defined   (con /cw addspawn mi_arena)
                   /cw set mi_arena min|max|time|teams <n>
6. /cw admin abre el menú GUI de administración. /cw info mi_arena revisa la configuración.

## Jugadores
/cw (menú), /cw join <arena>, /cw leave, /cw list, /cw top, /cw stats

## Permisos
colorwar.play (todos) · colorwar.admin (op)

Recompensas y ajustes: config.yml. Datos: arenas.yml y stats.yml.
