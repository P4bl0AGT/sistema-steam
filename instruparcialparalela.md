ICI-4344 Proyecto Parcial:
Computación Paralela y Distribuida
Profesor: Francisco Andres Muñoz Alarcon – francisco.munoz@pucv.cl
_________________________________________________________________________
1. Definición del Sistema
Desarrollar una aplicación distribuida en Java compuesta por procesos independientes que colaboren para
ejecutar dos funciones principales de gran escala.
Cada grupo debe:
1. Seleccionar un dominio de sistema distribuido (ej.: chat, sistema de reservas, banca en línea,
plataformas de delivery, etc.).
2. Definir claramente dos funciones principales del sistema que justifiquen la distribución.
3. Implementar la comunicación efectiva entre procesos remotos.
El sistema debe evidenciar:
1. Concurrencia de usuarios/procesos.
2. Manejo de fallos independientes.
3. Transparencia (mínimo de acceso y ubicación).
2. Pauta de Evaluación Detallada
I. Informe Técnico e Inspección de Modelos (40%)
● Requisito: Uso obligatorio del Formato de Informe de Pregrado de la Escuela.
● Entrega: Formato digital (PDF) en el aula virtual y copia impresa al momento de la presentación.
1.1 Fundamentación y Teoría (10%)
● Explicación de la concurrencia aplicada al sistema desarrollado.
● Identificación de posibles fallos independientes en los nodos.
● Justificación de transparencia: Detallar cómo se logra la transparencia de acceso y de ubicación en su
implementación.
1.2 Modelado de Ingeniería (15%)
● Modelo Físico: Identificación de nodos (clientes, servidores), descripción del entorno de red
(LAN/WAN) y representación gráfica de la interconexión.
● Modelo Arquitectónico: Definición de capas, componentes principales, tipo de arquitectura
(Cliente-Servidor, etc.) y flujo de comunicación.
● Modelado de Funciones: Diagramas de secuencia UML para las dos funciones principales, detallando
la interacción entre componentes.
1.3 Análisis Fundamental (15%)
● Modelo de Seguridad: Identificación de canales inseguros, descripción de amenazas (ataques
posibles) y propuestas de mitigación técnicas en Java.
● Modelo de Fallos: Clasificación de fallos esperados (Crash y Omisión) junto con las estrategias de
detección y recuperación programadas.
1

ICI-4344 Proyecto Parcial:
Computación Paralela y Distribuida
Profesor: Francisco Andres Muñoz Alarcon – francisco.munoz@pucv.cl
_________________________________________________________________________
II. Implementación y Código Fuente en Java (30%)
2.1 Comunicación y Marshalling (10%)
● Implementación de comunicación mediante Sockets (TCP o UDP).
● Uso de serialización o marshalling para el envío de estructuras de datos complejas entre procesos.
2.2 Gestión de Concurrencia (10%)
● Implementación de un servidor multi-hilo (un hilo por cliente o pool de hilos).
● Uso correcto de mecanismos de sincronización (synchronized, Locks) para la protección de
recursos compartidos.
2.3 Lógica de las Funciones Principales (10%)
● Correcto funcionamiento de las dos funciones de gran escala definidas.
● Manejo de excepciones: Control de errores de red y de ejecución para prevenir la caída total del
sistema ante fallos parciales.
III. Presentación, Demo y Defensa (30%)
● Requisito: La presentación debe ser coherente con el informe y el código entregado.
3.1 Exposición y Video (20%)
● Participación oral obligatoria de al menos 3 integrantes.
● Explicación clara de objetivos, modelos (Físico, Arquitectónico y UML) y su relación con la
implementación.
● Video Demostrativo: Grabación de máximo 3 minutos mostrando la ejecución del código y las
funciones principales.
● Demo en vivo: Validación rápida del funcionamiento en tiempo real.
3.2 Defensa Técnica (10%)
● Respuesta a preguntas del profesor sobre diseño, arquitectura y decisiones de código.
● Respuesta a preguntas de sus compañeros.
● Demostración de coherencia absoluta entre el Informe, el Código y la Presentación.
3. Entregables Obligatorios
● Aula Virtual (Digital):
1. Informe técnico en PDF (Formato Pregrado).
2. Proyecto de código fuente completo (.zip).
3. Presentación con el video demostrativo incluido o adjunto.
● En Sala (Físico):
1. Informe impreso para revisión técnica durante la exposición.
2

ICI-4344 Proyecto Parcial:
Computación Paralela y Distribuida
Profesor: Francisco Andres Muñoz Alarcon – francisco.munoz@pucv.cl
_________________________________________________________________________
4. Parámetros de Grupo y Tiempos
● Integrantes: Grupos de 5 a 6 personas.
● Tiempo total por grupo: 15 minutos de presentación + 5 minutos de preguntas.
5. Condiciones de Entrega y Sanciones
● La entrega fuera de plazo está permitida, pero con penalización.
● Se descontará 1.0 punto en la nota final por cada día de atraso.
6. Condición Obligatoria de Presentación
● Todos los grupos deben presentarse en la fecha y hora asignada.
● La presentación es obligatoria e independiente de la entrega del código/informe.
● En caso de inasistencia o no presentar, la calificación de presentacion automática será nota 1.0.
7. Consideración Final
El proyecto será evaluado bajo el rigor de la ingeniería, considerando la coherencia entre lo diseñado
(modelos), lo documentado (informe) y lo ejecutado (código), asegurando la correcta aplicación de los
conceptos de sistemas distribuidos estudiados.
8. Matriz de Valoración Detallada (Rúbrica de Ingeniería)
I. Informe Técnico e Inspección de Modelos (40%)
Criterio No Logrado (0%) Algo Logrado (33%) Bien Logrado (66%) Excelente (100%)
1.1 Teoría y No define los Menciona los Explica la mayoría de Vinculación técnica
Fundamentos conceptos o los conceptos pero no los conceptos completa: explica con
copia de la teoría explica cómo se (concurrencia, reloj, ejemplos del código cómo
sin aplicarlos al manifiestan en su fallos, transparencia) se maneja la concurrencia,
proyecto. software. vinculados al código. la ausencia de reloj global
y las transparencias
requeridas.
1.2 Modelado de Modelos ausentes Presenta modelos Presenta los tres Modelos profesionales y
Ingeniería o no corresponden pero con errores modelos. Son coherentes: el diagrama
al sistema graves de notación o coherentes con el físico, el de capas y el de
desarrollado. falta un modelo sistema, pero tienen secuencia reflejan
completo (físico, detalles menores en la completamente la
arquitectónico o UML). simbología UML. implementación del
sistema.
3

ICI-4344 Proyecto Parcial:
Computación Paralela y Distribuida
Profesor: Francisco Andres Muñoz Alarcon – francisco.munoz@pucv.cl
_________________________________________________________________________
1.3 Análisis  No realiza análisis  Identifica fallos y  Identifica canales  Análisis profundo: define
Fundamental  de seguridad ni de  amenazas de forma  inseguros y fallos  matriz de fallos con
| tolerancia a fallos.  | genérica sin proponer  | (crash, omisión)     | protocolos de             |
| --------------------- | ---------------------- | -------------------- | ------------------------- |
|                       | soluciones técnicas    | proponiendo          | recuperación y evalúa la  |
|                       | concretas.             | mitigaciones         | superficie de ataque con  |
|                       |                        | implementadas en el  | soluciones implementadas  |
|                       |                        | sistema.             | en Java.                  |

II. Implementación y Código Fuente en Java (30%)
Criterio  No Logrado (0%)  Algo Logrado (33%)  Bien Logrado (66%)  Excelente (100%)
2.1 Comunicación y  No hay comunicación  Hay comunicación  Implementa sockets y  Comunicación robusta:
Marshalling
| entre procesos   | por sockets, pero    | marshalling funcional.  | utiliza sockets         |
| ---------------- | -------------------- | ----------------------- | ----------------------- |
| independientes.  | solo envía texto     | Envía objetos o datos   | (TCP/UDP) y             |
|                  | simple (String) sin  | estructurados           | serialización avanzada  |
|                  | estructuras          | correctamente.          | para el intercambio de  |
|                  | complejas.           |                         | estados complejos       |
entre nodos.
2.2 Concurrencia
El servidor es iterativo  Usa hilos pero no  Servidor multihilo  Servidor concurrente
(atiende un cliente a la  protege los recursos  funcional. Usa  óptimo: un hilo por
vez) o no utiliza hilos.  compartidos (existen  mecanismos de  cliente (o pool de hilos)
|     | condiciones de       | sincronización,      | con uso adecuado de  |
| --- | -------------------- | -------------------- | -------------------- |
|     | carrera evidentes).  | aunque con aspectos  | synchronized o       |
|     |                      | mejorables.          | mecanismos de        |
bloqueo en regiones
críticas.
2.3 Lógica de  Las funciones no  Solo una función  Ambas funciones  Funcionalidad
Funciones  cumplen el objetivo o  opera correctamente  operan, pero el  completa: ambas
el código no  o el sistema es  sistema falla ante  funciones de gran
| compila/ejecuta.  | inestable ante         | errores de red por falta  | escala operan           |
| ----------------- | ---------------------- | ------------------------- | ----------------------- |
|                   | entradas del usuario.  | de manejo de              | correctamente y el      |
|                   |                        | excepciones.              | sistema es resilientea  |
excepciones de red.

III. Presentación, Demo y Defensa (30%)
Criterio  No Logrado (0%)  Algo Logrado (33%)  Bien Logrado (66%)  Excelente (100%)
3.1 Exposición
No hay video, la  El video excede los 3  Video correcto y demo  Presentación impecable:
y Video
demo no funciona o  minutos o la demo  funcional. Se explica la  video sintético (menos de 3
exponen menos de  presenta fallas. La  relación entre modelos y  minutos), demo en red real
tres integrantes.  explicación de los  código. Participan tres o  exitosa y dominio completo
|     | modelos es    | más integrantes.  | del escenario por parte de  |
| --- | ------------- | ----------------- | --------------------------- |
|     | superficial.  |                   | los expositores.            |
3.2 Defensa  No son capaces de  Responden con  Responden  Dominio total: justifican con
Técnica  explicar el código o  dudas o contradicen  correctamente la mayoría  argumentos de arquitectura
no responden  lo presentado en el  de las preguntas técnicas.  cada decisión de diseño y
| preguntas.  | informe.  |     | responden con precisión.  |
| ----------- | --------- | --- | ------------------------- |

4