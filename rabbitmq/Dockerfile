# STOMP broker behind cases-service's chat when it runs as more than one instance. The stock image
# ships the STOMP plugin disabled; enabling it at build time keeps the same image for compose and Railway.
FROM rabbitmq:4-alpine

RUN rabbitmq-plugins enable --offline rabbitmq_stomp

# STOMP only: cases-service is the one client, and nothing speaks AMQP to this broker.
EXPOSE 61613
