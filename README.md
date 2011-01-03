Mule AMQP Transport
===================

Supported AMQP Versions
-----------------------

This transport is based on the RabbitMQ Java Client, which is compatible with brokers supporting AMQP version 0.9.1.


Features
--------

- Inbound message receiving via subscription to existing or declared exchanges and queues.
- Outbound message publication to existing or declared exchanges.
- Outbound request-response pattern supported via temporary reply queues.
- Synchronous Message requesting with time-out.
- Passive or active-only exchange and queue declaration.
- Support for connection fallback accross a list of AMQP hosts.
- Support of all AMQP's message properties, including custom headers.
- Support of reply to (publishing replies to the default exchange).
- Support of automatic, Mule-driven and manual message acknowledgment.
- Support of the default exchange semantics in outbound endpoints.
- Support for mandatory and immediate publish parameters and handling of returned (undelivered) messages.
- Support for prefetch size and count "quality of service" settings.


Integration Testing
-------------------

Run:

    mvn -Pit clean verify

The integration tests rely on a locally running RabbitMQ broker and an OS that can run shell scripts (for the setup of the testing vhost and user).


Not (Yet) Supported
-------------------

- Local transactions