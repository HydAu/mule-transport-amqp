/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.amqp;

import java.io.IOException;

import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.expression.ExpressionManager;
import org.mule.api.transport.DispatchException;
import org.mule.config.i18n.MessageFactory;
import org.mule.transport.AbstractMessageDispatcher;
import org.mule.transport.amqp.AmqpConnector.OutboundConnection;
import org.mule.util.StringUtils;

import com.rabbitmq.client.AMQP.Queue.DeclareOk;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ReturnListener;

/**
 * The <code>AmqpMessageDispatcher</code> takes care of sending messages from Mule to an AMQP
 * broker. It supports synchronous sending by the means of private temporary reply queues.
 */
public class AmqpMessageDispatcher extends AbstractMessageDispatcher
{
    protected final AmqpConnector amqpConnector;
    protected OutboundConnection outboundConnection;

    protected enum OutboundAction
    {
        DISPATCH
        {
            @Override
            public AmqpMessage run(final AmqpConnector amqpConnector,
                                   final Channel channel,
                                   final String exchange,
                                   final String routingKey,
                                   final AmqpMessage amqpMessage,
                                   final long timeout) throws IOException
            {
                channel.basicPublish(exchange, routingKey, amqpConnector.isMandatory(),
                    amqpConnector.isImmediate(), amqpMessage.getProperties(), amqpMessage.getBody());
                return null;
            }
        },
        SEND
        {
            @Override
            public AmqpMessage run(final AmqpConnector amqpConnector,
                                   final Channel channel,
                                   final String exchange,
                                   final String routingKey,
                                   final AmqpMessage amqpMessage,
                                   final long timeout) throws IOException, InterruptedException
            {
                final DeclareOk declareOk = channel.queueDeclare();
                final String temporaryReplyToQueue = declareOk.getQueue();
                amqpMessage.setReplyTo(temporaryReplyToQueue);

                DISPATCH.run(amqpConnector, channel, exchange, routingKey, amqpMessage, timeout);
                return amqpConnector.consume(channel, temporaryReplyToQueue, true, timeout);
            }
        };

        public abstract AmqpMessage run(final AmqpConnector amqpConnector,
                                        Channel channel,
                                        String exchange,
                                        String routingKey,
                                        AmqpMessage amqpMessage,
                                        final long timeout) throws IOException, InterruptedException;
    };

    public AmqpMessageDispatcher(final OutboundEndpoint endpoint)
    {
        super(endpoint);
        amqpConnector = (AmqpConnector) endpoint.getConnector();
        if (logger.isDebugEnabled())
        {
            logger.debug("Instantiated: " + this);
        }
    }

    @Override
    protected void doConnect() throws MuleException
    {
        outboundConnection = amqpConnector.connect(this);
    }

    @Override
    protected void doDisconnect() throws MuleException
    {
        final Channel channel = getChannel();

        if (logger.isDebugEnabled())
        {
            logger.debug("Disconnecting: exchange: " + getExchange() + " from channel: " + channel);
        }

        outboundConnection = null;
        amqpConnector.closeChannel(channel);
    }

    @Override
    public void doDispatch(final MuleEvent event) throws Exception
    {
        doOutboundAction(event, OutboundAction.DISPATCH);
    }

    @Override
    public MuleMessage doSend(final MuleEvent event) throws Exception
    {
        final MuleMessage resultMessage = createMuleMessage(doOutboundAction(event, OutboundAction.SEND));
        resultMessage.applyTransformers(event, amqpConnector.getReceiveTransformer());
        return resultMessage;
    }

    protected AmqpMessage doOutboundAction(final MuleEvent event, final OutboundAction outboundAction)
        throws Exception
    {
        final MuleMessage message = event.getMessage();

        if (!(message.getPayload() instanceof AmqpMessage))
        {
            throw new DispatchException(
                MessageFactory.createStaticMessage("Message payload is not an instance of: "
                                                   + AmqpMessage.class.getName()), event, getEndpoint());
        }

        final Channel eventChannel = getChannel();

        String eventExchange = message.getOutboundProperty(AmqpConstants.EXCHANGE, getExchange());
        if (AmqpEndpointUtil.isDefaultExchange(eventExchange))
        {
            eventExchange = "";
        }

        String eventRoutingKey = message.getOutboundProperty(AmqpConstants.ROUTING_KEY, getRoutingKey());
        // MEL in exchange and queue is auto-resolved as being part of the endpoint URI but routing
        // key must be resolved by hand
        final ExpressionManager expressionManager = event.getMuleContext().getExpressionManager();
        if (expressionManager.isValidExpression(eventRoutingKey))
        {
            eventRoutingKey = expressionManager.evaluate(eventRoutingKey, event).toString();
        }

        final AmqpMessage amqpMessage = (AmqpMessage) message.getPayload();

        // override publication properties if they are not set
        if ((amqpMessage.getProperties().getDeliveryMode() == null)
            && (amqpConnector.getDeliveryMode() != null))
        {
            amqpMessage.setDeliveryMode(amqpConnector.getDeliveryMode());
        }
        if ((amqpMessage.getProperties().getPriority() == null) && (amqpConnector.getPriority() != null))
        {
            amqpMessage.setPriority(amqpConnector.getPriority().intValue());
        }

        addReturnListenerIfNeeded(event, eventChannel);

        final AmqpMessage result = outboundAction.run(amqpConnector, eventChannel, eventExchange,
            eventRoutingKey, amqpMessage, getTimeOutForEvent(event));

        if (logger.isDebugEnabled())
        {
            logger.debug(String.format(
                "Successfully performed %s(channel: %s, exchange: %s, routing key: %s) for: %s and received: %s",
                outboundAction, eventChannel, eventExchange, eventRoutingKey, event, result));
        }

        return result;
    }

    private int getTimeOutForEvent(final MuleEvent muleEvent)
    {
        final int defaultTimeOut = muleEvent.getMuleContext().getConfiguration().getDefaultResponseTimeout();
        final int eventTimeOut = muleEvent.getTimeout();

        // allow event time out to override endpoint response time
        if (eventTimeOut != defaultTimeOut)
        {
            return eventTimeOut;
        }
        return getEndpoint().getResponseTimeout();
    }

    /**
     * Try to associate a return listener to the channel in order to allow flow-level exception
     * strategy to handle return messages.
     */
    protected void addReturnListenerIfNeeded(final MuleEvent event, final Channel channel)
    {
        final ReturnListener returnListener = event.getMessage().getInvocationProperty(
            AmqpConstants.RETURN_LISTENER);

        if (returnListener == null)
        {
            // no return listener defined in the flow that encompasses the event
            return;
        }

        if (returnListener instanceof AmqpReturnHandler.DispatchingReturnListener)
        {
            ((AmqpReturnHandler.DispatchingReturnListener) returnListener).setAmqpConnector(amqpConnector);
        }

        channel.addReturnListener(returnListener);

        if (logger.isDebugEnabled())
        {
            logger.debug(String.format("Set return listener: %s on channel: %s", returnListener, channel));
        }
    }

    protected Channel getChannel()
    {
        return outboundConnection == null ? null : outboundConnection.getChannel();
    }

    protected String getExchange()
    {
        return outboundConnection == null ? StringUtils.EMPTY : outboundConnection.getExchange();
    }

    protected String getRoutingKey()
    {
        return outboundConnection == null ? StringUtils.EMPTY : outboundConnection.getRoutingKey();
    }
}
