package com.tb24.discordbot.util

import com.tb24.discordbot.util.CollectorEndReason.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.interactions.components.ComponentInteraction
import java.util.concurrent.CompletableFuture

open class MessageComponentInteractionCollectorOptions : CollectorOptions() {
	var max: Int? = null
	var maxComponents: Int? = null
	var maxUsers: Int? = null
}

class MessageComponentInteractionCollector : Collector<ComponentInteraction, MessageComponentInteractionCollectorOptions> {
	var message: Message? = null
	val channel: MessageChannel
	val users = mutableMapOf<Long, User>()
	var total = 0

	constructor(message: Message, filter: CollectorFilter<ComponentInteraction>, options: MessageComponentInteractionCollectorOptions) : this(message.channel, filter, options) {
		this.message = message
	}

	constructor(channel: MessageChannel, filter: CollectorFilter<ComponentInteraction>, options: MessageComponentInteractionCollectorOptions) : super(channel.jda, filter, options) {
		this.channel = channel
		client.addEventListener(this)
	}

	// region ListenerAdapter interface
	override fun onGenericComponentInteractionCreate(event: GenericComponentInteractionCreateEvent) {
		handleCollect(event.interaction, event.user)
	}

	override fun onMessageDelete(event: MessageDeleteEvent) {
		val message = message ?: return
		if (event.messageIdLong == message.idLong) {
			stop(MESSAGE_DELETE)
		}
	}

	override fun onChannelDelete(event: ChannelDeleteEvent) {
		if (event.channel.idLong == channel.idLong) {
			stop(CHANNEL_DELETE)
		}
	}
	// endregion

	override fun onCollect(item: ComponentInteraction, user: User?) {
		super.onCollect(item, user)
		total++
		if (user != null) users[user.idLong] = user
	}

	override fun collect(item: ComponentInteraction, user: User?): Any? {
		val message = message
		if (message != null) {
			return if (item.messageIdLong == message.idLong) item.idLong else null
		}
		return if (item.messageIdLong == channel.idLong) item.idLong else null
	}

	override fun dispose(item: ComponentInteraction, user: User?): Any? {
		val message = message
		if (message != null) {
			return if (item.messageIdLong == message.idLong) item.idLong else null
		}
		return if (item.messageIdLong == channel.idLong) item.idLong else null
	}

	override fun endReason() = when {
		options.max != null && total >= options.max!! -> LIMIT
		options.maxComponents != null && collected.size == options.maxComponents!! -> COMPONENT_LIMIT
		options.maxUsers != null && users.size >= options.maxUsers!! -> USER_LIMIT
		else -> null
	}
	// endregion

	fun empty() {
		total = 0
		collected.clear()
		users.clear()
		checkEnd()
	}
}

inline fun Message.createMessageComponentInteractionCollector(noinline filter: CollectorFilter<ComponentInteraction>, options: MessageComponentInteractionCollectorOptions = MessageComponentInteractionCollectorOptions()) =
	MessageComponentInteractionCollector(this, filter, options)

inline fun MessageChannel.createMessageComponentInteractionCollector(noinline filter: CollectorFilter<ComponentInteraction>, options: MessageComponentInteractionCollectorOptions = MessageComponentInteractionCollectorOptions()) =
	MessageComponentInteractionCollector(this, filter, options)

class AwaitMessageComponentInteractionsOptions : MessageComponentInteractionCollectorOptions() {
	var errors: Array<CollectorEndReason>? = null
	var finalizeComponentsOnEnd = true
}

@Throws(CollectorException::class)
fun Message.awaitMessageComponentInteractions(filter: CollectorFilter<ComponentInteraction>, options: AwaitMessageComponentInteractionsOptions = AwaitMessageComponentInteractionsOptions()): CompletableFuture<Collection<ComponentInteraction>> {
	val future = CompletableFuture<Collection<ComponentInteraction>>()
	val collector = createMessageComponentInteractionCollector(filter, options)
	collector.callback = object : CollectorListener<ComponentInteraction> {
		override fun onCollect(item: ComponentInteraction, user: User?) {}
		override fun onRemove(item: ComponentInteraction, user: User?) {}
		override fun onDispose(item: ComponentInteraction, user: User?) {}

		override fun onEnd(collected: Map<Any, ComponentInteraction>, reason: CollectorEndReason) {
			if (options.errors?.contains(reason) == true) {
				future.completeExceptionally(CollectorException(collector, reason))
				finalizeComponents(collected.values.map { it.componentId })
			} else {
				future.complete(collected.values)
				if (options.finalizeComponentsOnEnd) {
					finalizeComponents(collected.values.map { it.componentId })
				}
			}
		}
	}
	return future
}