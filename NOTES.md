# Esta guía demuestra cómo su aplicación Quarkus puede utilizar SmallRye Reactive Messaging para interactuar con Apache Kafka.

## Prerequisites

*   Para completar esta guía, necesitas
*   Un IDE
*   JDK 11+ instalado con JAVA\_HOME configurado adecuadamente
*   Apache Maven 3.8.6
*   Docker y Docker Compose o Podman, y Docker Compose
*   Opcionalmente el CLI de Quarkus si quieres usarlo
*   Opcionalmente Mandrel o GraalVM instalado y configurado apropiadamente si quieres construir un ejecutable nativo (o Docker si usas un contenedor nativo)

## Arquitectura

En esta guía, vamos a desarrollar dos aplicaciones que se comunican con Kafka. La primera aplicación envía una petición de presupuesto a Kafka y consume mensajes Kafka del tema de presupuesto. La segunda aplicación recibe la solicitud de presupuesto y envía un presupuesto de vuelta.
![enter image description here](https://quarkus.io/guides/images/kafka-qs-architecture.png)

La primera aplicación, el productor, permitirá al usuario solicitar algunas cotizaciones a través de un endpoint HTTP. Para cada solicitud de presupuesto se genera un identificador aleatorio que se devuelve al usuario para marcar la solicitud de presupuesto como pendiente. Al mismo tiempo, el identificador de solicitud generado se envía a través de un tema Kafka **quote-requests**.

![enter image description here](https://quarkus.io/guides/images/kafka-qs-app-screenshot.png)

La segunda aplicación, el procesador, leerá del tema **quote-requests**, pondrá un precio aleatorio a la cotización y la enviará a un tema Kafka llamado **quotes**.

Por último, el productor leerá las cotizaciones y las enviará al navegador mediante eventos enviados por el servidor. De este modo, el usuario verá el precio de la cotización actualizado de pendiente al precio recibido en tiempo real.

## Solución
Le recomendamos que siga las instrucciones de las siguientes secciones y cree las aplicaciones paso a paso. No obstante, puede ir directamente al ejemplo completado.

## Creating the Maven Project

* CLI
```bash
quarkus create app org.acme:kafka-quickstart-producer \
    --extension='resteasy-reactive-jackson,smallrye-reactive-messaging-kafka' \
    --no-code
```
* MAVEN
```bash
quarkus create app org.acme:kafka-quickstart-producer \
    --extension='resteasy-reactive-jackson,smallrye-reactive-messaging-kafka' \
    --no-code
```
Este comando crea la estructura del proyecto y selecciona dos extensiones de Quarkus que vamos a utilizar:

1. RESTEasy Reactive y su soporte Jackson (para manejar JSON) para servir el endpoint HTTP.

2. El conector Kafka para Reactive Messaging

Para crear el proyecto procesador, desde el mismo directorio, ejecuta:

* CLI
```bash
quarkus create app org.acme:kafka-quickstart-processor \
    --extension='smallrye-reactive-messaging-kafka' \
    --no-code
```
* MAVEN
```bash
mvn io.quarkus.platform:quarkus-maven-plugin:2.16.3.Final:create \
    -DprojectGroupId=org.acme \
    -DprojectArtifactId=kafka-quickstart-processor \
    -Dextensions='smallrye-reactive-messaging-kafka' \
    -DnoCode
```
En ese momento, deberías tener la siguiente estructura:
```text
├── kafka-processor
│  ├── README.md
│  ├── mvnw
│  ├── mvnw.cmd
│  ├── pom.xml
│  └── src
│     └── main
│        ├── docker
│        ├── java
│        └── resources
│           └── application.properties
└── kafka-producer
   ├── README.md
   ├── mvnw
   ├── mvnw.cmd
   ├── pom.xml
   └── src
      └── main
         ├── docker
         ├── java
         └── resources
            └── application.properties
```
## El objeto Quote

La clase **Quote** se utilizará tanto en los proyectos productores como en los procesadores. Para simplificar, duplicaremos la clase. En ambos proyectos, cree el archivo **src/main/java/org/acme/kafka/model/Quote.java**, con el siguiente contenido:
```java
package org.acme.kafka.model;  
  
import lombok.AllArgsConstructor;  
import lombok.Builder;  
import lombok.Data;  
import lombok.RequiredArgsConstructor;  
  
@Data  
@Builder  
@RequiredArgsConstructor  
@AllArgsConstructor  
public class Quote {  
    public String id;  
 public int price;  
}
```
La representación JSON de los objetos **Quote** se utilizará en los mensajes enviados al tema Kafka y también en los eventos enviados por el servidor a los navegadores web.

Quarkus tiene capacidades incorporadas para tratar con mensajes JSON Kafka. En una sección siguiente, crearemos clases serializadoras/deserializadoras para Jackson.

## Envío de solicitud de cotización
Dentro del proyecto producer, crea el archivo **src/main/java/org/acme/kafka/producer/QuotesResource.java** y añade el siguiente contenido:

```java
package org.acme.kafka.producer;  
  
import io.smallrye.mutiny.Multi;  
import org.acme.kafka.model.Quote;  
import org.eclipse.microprofile.reactive.messaging.Channel;  
import org.eclipse.microprofile.reactive.messaging.Emitter;  
  
import javax.ws.rs.GET;  
import javax.ws.rs.POST;  
import javax.ws.rs.Path;  
import javax.ws.rs.Produces;  
import javax.ws.rs.core.MediaType;  
import java.util.UUID;  
  
@Path("/quotes")  
public class QuotesResource {  
  
    @Channel("quote-requests")  
    Emitter<String> quoteRequestEmitter;  (1)
  
  /**  
 * Endpoint to generate a new quote request id and send it to "quote-requests" Kafka topic using the emitter. */  @POST  
 @Path("/request")  
    @Produces(MediaType.TEXT_PLAIN)  
    public String createRequest() {  
        UUID uuid = UUID.randomUUID();  
  quoteRequestEmitter.send(uuid.toString()); (2) 
 return uuid.toString();  (3)
  }  
  
    @Channel("quotes")  
    Multi<Quote> quotes;  
  
  /**  
 * Endpoint retrieving the "quotes" Kafka topic and sending the items to a server sent event. */  @GET  
 @Produces(MediaType.SERVER_SENT_EVENTS) // denotes that server side events (SSE) will be produced  
  public Multi<Quote> stream() {  
        return quotes.log();  
  }  
}
```
(1) Inyecta un emisor de mensajería reactiva para enviar mensajes al canal quote-requests.
(2) En una solicitud de publicación, generar un UUID aleatorio y enviarlo al tema Kafka utilizando el emisor.
(3) Devuelve el mismo UUID al cliente.

El canal **quote-requests** va a ser gestionado como un tema Kafka, ya que es el único conector en el classpath. Si no se indica lo contrario, como en este ejemplo, Quarkus utiliza el nombre del canal como nombre del tema. Así, en este ejemplo, la aplicación escribe en el topic **quote-requests**. Quarkus también configura el serializador automáticamente, porque encuentra que el **Emitter(Emisor)** produce valores **String**.

## Procesar solicitudes de presupuesto
Ahora vamos a consumir la solicitud de presupuesto y dar un precio. Dentro del proyecto procesador, crea el archivo **src/main/java/org/acme/kafka/processor/QuotesProcessor.java** y añade el siguiente contenido:

```java
package org.acme.kafka.processor;  
  
import java.util.Random;  
  
import javax.enterprise.context.ApplicationScoped;  
  
import org.acme.kafka.model.Quote;  
import org.eclipse.microprofile.reactive.messaging.Incoming;  
import org.eclipse.microprofile.reactive.messaging.Outgoing;  
  
import io.smallrye.reactive.messaging.annotations.Blocking;  
  
/**  
 * A bean consuming data from the "quote-requests" Kafka topic (mapped to "requests" channel) and giving out a random quote. * The result is pushed to the "quotes" Kafka topic. */@ApplicationScoped  
public class QuotesProcessor {  
  
    private Random random = new Random();  
  
  @Incoming("requests") (1)  
  @Outgoing("quotes")   (2)
  @Blocking  			(3)
  public Quote process(String quoteRequest) throws InterruptedException {  
        // Simulamos que la tarea tarda  
  Thread.sleep(200);  
 return new Quote(quoteRequest, random.nextInt(100));  
  }  
}
```
(1) Indica que el método consume los elementos del canal de peticiones.
(2) Indica que los objetos devueltos por el método se envían al canal de peticiones.
(3) Indica que el procesamiento es bloqueante y no puede ejecutarse en el hilo de la llamada.
Por cada registro Kafka del topic **quote-requests**, Reactive Messaging llama al método **process**, y envía el objeto Quote devuelto al canal **quotes**. En este caso, necesitamos configurar el canal en el archivo **application.properties**, para configurar los canales **requests** y **quotes**:

  ```memraid
%dev.quarkus.http.port=8081  
 
kafka.auto.offset.reset=earliest  
 
mp.messaging.incoming.requests.topic=quote-requests  
mp.messaging.outgoing.quotes.value.serializer=io.quarkus.kafka.client.serialization.ObjectMapperSerializer
```
Nótese que en este caso tenemos una configuración de conectores entrantes y otra de conectores salientes, cada una con un nombre distinto. Las claves de configuración se estructuran de la siguiente manera

**mp.messaging.[outgoing|incoming].{nombre-canal}.property=valor**

El segmento **nombre-canal** debe coincidir con el valor establecido en la anotación **@Incoming** y **@Outgoing**:

**quote-requests** → tema Kafka del que leemos las solicitudes de cotización.

**quotes** → tema de Kafka en el que escribimos los presupuestos.

**mp.messaging.incoming.requests.auto.offset.reset=earliest**
indica a la aplicación que comience a leer los temas desde el primer offset, cuando no haya un offset comprometido para el grupo de consumidores. En otras palabras, también procesará los mensajes enviados antes de que iniciemos la aplicación procesadora.

No es necesario establecer serializadores o deserializadores. Quarkus los detecta, y si no se encuentra ninguno, los genera utilizando serialización JSON.

# Recibir quotes
Volvamos a nuestro proyecto productor. Vamos a modificar el **QuotesResource** para consumir cotizaciones de Kafka y enviarlas de vuelta al cliente a través de Server-Sent Events:

```java
package org.acme.kafka.producer;  
  
import io.smallrye.mutiny.Multi;  
import org.acme.kafka.model.Quote;  
import org.eclipse.microprofile.reactive.messaging.Channel;  
import org.eclipse.microprofile.reactive.messaging.Emitter;  
  
import javax.ws.rs.GET;  
import javax.ws.rs.POST;  
import javax.ws.rs.Path;  
import javax.ws.rs.Produces;  
import javax.ws.rs.core.MediaType;  
import java.util.UUID;  
  
@Path("/quotes")  
public class QuotesResource {  
  
    @Channel("quote-requests")  
    Emitter<String> quoteRequestEmitter;  
  
  /**  
 * Endpoint to generate a new quote request id and send it to "quote-requests" Kafka topic using the emitter. */  @POST  
 @Path("/request")  
    @Produces(MediaType.TEXT_PLAIN)  
    public String createRequest() {  
        UUID uuid = UUID.randomUUID();  
  quoteRequestEmitter.send(uuid.toString());  
 return uuid.toString();  
  }  
  
 @Channel("quotes")  
 Multi<Quote> quotes;  (1)
  
  /**  
 * Endpoint retrieving the "quotes" Kafka topic and sending the items to a server sent event. */  @GET  
 @Produces(MediaType.SERVER_SENT_EVENTS) (2)  
  public Multi<Quote> stream() {  
        return quotes.log();  (3)
  }  
}
```
(1) Inyecta el canal de citas utilizando el calificador @Channel
(2) Indica que el contenido se envía utilizando Server Sent Events
(3) Devuelve el flujo (Reactive Stream)

No es necesario configurar nada, ya que Quarkus asociará automáticamente el canal quotes al topic Kafka quotes. También generará un deserializador para la clase Quote.

## Página HTML
Toque final, la página HTML solicitando cotizaciones y mostrando los precios obtenidos a través de SSE.

Dentro del proyecto productor, cree el archivo **src/main/resources/META-INF/resources/quotes.html** con el siguiente contenido:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Prices</title>

    <link rel="stylesheet" type="text/css"
          href="https://cdnjs.cloudflare.com/ajax/libs/patternfly/3.24.0/css/patternfly.min.css">
    <link rel="stylesheet" type="text/css"
          href="https://cdnjs.cloudflare.com/ajax/libs/patternfly/3.24.0/css/patternfly-additions.min.css">
</head>
<body>
<div class="container">
    <div class="card">
        <div class="card-body">
            <h2 class="card-title">Quotes</h2>
            <button class="btn btn-info" id="request-quote">Request Quote</button>
            <div class="quotes"></div>
        </div>
    </div>
</div>
</body>
<script src="https://code.jquery.com/jquery-3.6.0.min.js"></script>
<script>
    $("#request-quote").click((event) => {
        fetch("/quotes/request", {method: "POST"})
        .then(res => res.text())
        .then(qid => {
            var row = $(`<h4 class='col-md-12' id='${qid}'>Quote # <i>${qid}</i> | <strong>Pending</strong></h4>`);
            $(".quotes").prepend(row);
        });
    });

    var source = new EventSource("/quotes");
    source.onmessage = (event) => {
      var json = JSON.parse(event.data);
      $(`#${json.id}`).html((index, html) => {
        return html.replace("Pending", `\$\xA0${json.price}`);
      });
    };
</script>
</html>
```
Cuando el usuario pulsa el botón, se realiza una petición HTTP para solicitar un quote, y se añade un quote pendiente a la lista. En cada quote recibido a través de SSE, se actualiza el elemento correspondiente de la lista.

## En marcha
Sólo tienes que ejecutar ambas aplicaciones. En un terminal ejecutar:
```bash
mvn -f producer quarkus:dev
```
```bash
mvn -f processor quarkus:dev
```
Quarkus inicia un broker de Kafka automáticamente, configura la aplicación y comparte la instancia del broker de Kafka entre diferentes aplicaciones. Consulte [Dev Services for Kafka](https://quarkus.io/guides/kafka-dev-services) para obtener más detalles.
Abra **http://localhost:8080/quotes.html** en su navegador y solicite unos presupuestos haciendo clic en el botón.

## Ejecución en modo JVM o Nativo
Cuando no se esté ejecutando en modo de desarrollo o de prueba, tendrás que iniciar tu broker de Kafka. Puede seguir las instrucciones del sitio web de Apache Kafka o crear un archivo docker-compose.yaml con el siguiente contenido:
```yaml
version: '3.5'

services:

  zookeeper:
    image: quay.io/strimzi/kafka:0.23.0-kafka-2.8.0
    command: [
      "sh", "-c",
      "bin/zookeeper-server-start.sh config/zookeeper.properties"
    ]
    ports:
      - "2181:2181"
    environment:
      LOG_DIR: /tmp/logs
    networks:
      - kafka-quickstart-network

  kafka:
    image: quay.io/strimzi/kafka:0.23.0-kafka-2.8.0
    command: [
      "sh", "-c",
      "bin/kafka-server-start.sh config/server.properties --override listeners=$${KAFKA_LISTENERS} --override advertised.listeners=$${KAFKA_ADVERTISED_LISTENERS} --override zookeeper.connect=$${KAFKA_ZOOKEEPER_CONNECT}"
    ]
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      LOG_DIR: "/tmp/logs"
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
    networks:
      - kafka-quickstart-network

  producer:
    image: quarkus-quickstarts/kafka-quickstart-producer:1.0-${QUARKUS_MODE:-jvm}
    build:
      context: producer
      dockerfile: src/main/docker/Dockerfile.${QUARKUS_MODE:-jvm}
    depends_on:
      - kafka
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    ports:
      - "8080:8080"
    networks:
      - kafka-quickstart-network

  processor:
    image: quarkus-quickstarts/kafka-quickstart-processor:1.0-${QUARKUS_MODE:-jvm}
    build:
      context: processor
      dockerfile: src/main/docker/Dockerfile.${QUARKUS_MODE:-jvm}
    depends_on:
      - kafka
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    networks:
      - kafka-quickstart-network

networks:
  kafka-quickstart-network:
    name: kafkaquickstart
```
Asegúrate de compilar primero ambas aplicaciones en modo JVM con:
```bash
mvn -f producer package
mvn -f processor package
```
Una vez empaquetado, ejecute **docker-compose up**.

También puedes compilar y ejecutar nuestras aplicaciones como ejecutables nativos. En primer lugar, compila ambas aplicaciones como nativas:
```bash
mvn -f producer package -Dnative -Dquarkus.native.container-build=true
mvn -f processor package -Dnative -Dquarkus.native.container-build=true
```
Run the system
```bash
export QUARKUS_MODE=native
docker-compose up --build
```
