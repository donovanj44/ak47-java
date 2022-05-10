package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.commands.arguments.UserArgument
import com.tb24.discordbot.managers.PartyManager
import com.tb24.discordbot.util.awaitOneInteraction
import com.tb24.discordbot.util.exec
import com.tb24.fn.model.account.GameProfile
import com.tb24.fn.model.party.FMemberInfo
import com.tb24.fn.model.party.FPartyInfo
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle

class PartyCommand : BrigadierCommand("party", "Manages your party.", arrayOf("p")) {
	// party: shows active party
	// party invite <user>: invites user to party
	// party kick <user>: kicks user from party
	// party promote <user>: promotes user to party leader
	// party leave: leaves party
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { party(it.source) }
		.then(literal("invite").then(argument("user", UserArgument.users(1)).executes { partyInvite(it.source, UserArgument.getUsers(it, "user").values.first()) }))
		.then(literal("kick").then(argument("user", UserArgument.users(1)).executes { partyKick(it.source, UserArgument.getUsers(it, "user").values.first()) }))
		.then(literal("promote").then(argument("user", UserArgument.users(1)).executes { partyPromote(it.source, UserArgument.getUsers(it, "user").values.first()) }))
		.then(literal("leave").executes { partyLeave(it.source) })

	private fun party(source: CommandSourceStack): Int {
		source.ensureSession()
		source.loading("Getting party info")
		val partyManager = source.session.getPartyManager(source.api.currentLoggedIn.id)
		partyManager.fetchParty()
		var party = partyManager.partyInfo ?: throw SimpleCommandExceptionType(LiteralMessage("You are not in a party.")).create()
		// **Party**
		// Members
		// - a
		// - [Leader] b
		// - ...
		// [Manage member] [Leave]

		// **Manage <member>**
		// [Promote] [Kick]

		val embed = source.createEmbed()
		val selfIsLeader = party.members.firstOrNull { it.account_id == source.api.currentLoggedIn.id }?.role == FMemberInfo.EPartyMemberRole.CAPTAIN
		val buttons = mutableListOf<Button>()
		if (selfIsLeader && party.members.size > 1) {
			buttons.add(Button.of(ButtonStyle.SECONDARY, "manageMembers", "Manage members"))
		}
		buttons.add(Button.of(ButtonStyle.DANGER, "leaveParty", "Leave party"))
		while (true) {
			partyManager.fetchParty()
			party = partyManager.partyInfo ?: throw SimpleCommandExceptionType(LiteralMessage("You are not in a party.")).create()
			val members = party.members.map {
				val isLeader = it.role == FMemberInfo.EPartyMemberRole.CAPTAIN
				val displayName = it.meta.getString("urn:epic:member:dn", source.userCache[it.account_id]?.displayName ?: "`${it.account_id}`")
				val memberText = if (isLeader) "👑 $displayName" else displayName
				if (source.api.currentLoggedIn.id  == it.account_id) "**$memberText** <- You" else memberText
			}
			val membersField = MessageEmbed.Field("Members", members.joinToString("\n"), false)
			embed.addField(membersField)
			val message = source.complete(null, embed.build(), ActionRow.of(buttons))
			embed.fields.remove(membersField)
			source.loadingMsg = message
			when (message.awaitOneInteraction(source.author, false).componentId) {
				"manageMembers" -> {
					// Choose member to manage
					val memberPickerButtons = mutableListOf<Button>()
					party.members.forEach {
						val displayName = it.meta.getString("urn:epic:member:dn", source.userCache[it.account_id]?.displayName ?: "`${it.account_id}`")
						if (it.account_id != source.api.currentLoggedIn.id) {
							memberPickerButtons.add(Button.of(ButtonStyle.SECONDARY, it.account_id, displayName))
						}
					}
					val memberPickerMessage = source.complete("**Choose member**", null, ActionRow.of(memberPickerButtons))
					source.loadingMsg = memberPickerMessage
					val memberId = memberPickerMessage.awaitOneInteraction(source.author, false).componentId
					if (manageMember(party, memberId, source, selfIsLeader, partyManager) == 0) {
						break
					}
				}
				"leaveParty" -> {
					partyManager.kick(source.api.currentLoggedIn.id)
					source.complete(null, source.createEmbed().setDescription("✅ You have left the party.").build())
					break
				}
				else -> break
			}
		}
		return Command.SINGLE_SUCCESS
	}

	private fun manageMember(party: FPartyInfo, memberId: String, source: CommandSourceStack, selfIsLeader: Boolean, partyManager: PartyManager): Int {
		val member = party.members.firstOrNull { it.account_id == memberId } ?: throw SimpleCommandExceptionType(LiteralMessage("Member not found.")).create()
		val memberDisplayName = member.meta.getString("urn:epic:member:dn", source.userCache[member.account_id]?.displayName ?: "`${member.account_id}`")
		// Manage member
		// Leader is internally called captain
		val memberButtons = mutableListOf<Button>()
		val memberIsSelf = member.account_id == source.api.currentLoggedIn.id
		if (selfIsLeader && !memberIsSelf) {
			memberButtons.add(Button.of(ButtonStyle.SECONDARY, "promote", "Promote to leader"))
			memberButtons.add(Button.of(ButtonStyle.DANGER, "kick", "Kick from party"))
		}
		if (memberIsSelf) {
			memberButtons.add(Button.of(ButtonStyle.DANGER, "leave", "Leave party"))
		}
		// cancel
		memberButtons.add(Button.of(ButtonStyle.SECONDARY, "cancel", "Cancel"))
		val memberMessage = source.complete("**Manage $memberDisplayName**", null, ActionRow.of(memberButtons))
		source.loadingMsg = memberMessage
		return when (memberMessage.awaitOneInteraction(source.author, false).componentId) {
			"promote" -> {
				partyManager.promote(member.account_id)
				source.channel.sendMessage("**$memberDisplayName** is now leader.").queue()
				Command.SINGLE_SUCCESS
			}
			"kick" -> {
				partyManager.kick(member.account_id)
				source.channel.sendMessage("**$memberDisplayName** has been kicked from the party.").queue()
				Command.SINGLE_SUCCESS
			}
			"leave" -> {
				partyManager.kick(member.account_id)
				source.complete(null, source.createEmbed().setDescription("✅ You have left the party.").build())
				0
			}
			"cancel" -> Command.SINGLE_SUCCESS
			else -> 0
		}
	}
}

class PartyKickAllCommand : BrigadierCommand("kickall", "Kicks all party members", arrayOf("ka")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes { c ->
			val source = c.source
			source.loading("Getting party info")
			source.ensureSession()
			val partyManager = source.session.getPartyManager(source.api.currentLoggedIn.id)
			partyManager.fetchParty()
			val partyInfo = partyManager.partyInfo ?: throw SimpleCommandExceptionType(LiteralMessage("You are not in a party.")).create()
			if (partyInfo.members.size == 1) {
				throw SimpleCommandExceptionType(LiteralMessage("You are the only member in the party.")).create()
			}
			var numKicked = 0
			if (partyInfo.members.firstOrNull { it.account_id == source.api.currentLoggedIn.id }!!.role == FMemberInfo.EPartyMemberRole.CAPTAIN ) {
				for(member in partyInfo!!.members) {
					if (member.account_id == source.api.currentLoggedIn.id) {
						continue
					}
					partyManager.kick(member.account_id)
					numKicked++
				}
			} else {
				throw SimpleCommandExceptionType(LiteralMessage("You are not the party leader.")).create()
			}
			source.complete(null, source.createEmbed().setDescription("✅ Successfully kicked %,d party member%s.".format(numKicked, if (numKicked == 1) "" else "s")).build())
			Command.SINGLE_SUCCESS
		}
}

private fun partyInvite(source: CommandSourceStack, user: GameProfile): Int {
	source.ensureSession()
	source.loading("Getting party info")
	val partyManager = source.session.getPartyManager(source.api.currentLoggedIn.id)
	partyManager.fetchParty()
	val party = partyManager.partyInfo ?: throw SimpleCommandExceptionType(LiteralMessage("You are not in a party.")).create()
	if (party.members.firstOrNull { it.account_id == user.id } != null) {
		throw SimpleCommandExceptionType(LiteralMessage("**%s** is already in the party.".format(user.displayName))).create()
	}
	source.api.friendsService.queryFriends(source.api.currentLoggedIn.id, true).exec().body()!!
		.firstOrNull { it.accountId == user.id} ?: throw SimpleCommandExceptionType(LiteralMessage("**%s** is not in your friend list".format(user.displayName))).create()
	val invite = party.invites.firstOrNull { it.sent_to == user.id }
	if (invite != null) {
		partyManager.ping(user.id)
	} else {
		partyManager.invite(user.id)
	}
	source.complete(null, source.createEmbed().setDescription("✅ Invited **%s** to the party.".format(user.displayName)).build())
	return Command.SINGLE_SUCCESS
}

private fun partyKick(source: CommandSourceStack, user: GameProfile): Int {
	source.ensureSession()
	source.loading("Getting party info")
	val partyManager = source.session.getPartyManager(source.api.currentLoggedIn.id)
	partyManager.fetchParty()
	val party = partyManager.partyInfo ?: throw SimpleCommandExceptionType(LiteralMessage("You are not in a party.")).create()
	if (party.members.firstOrNull { it.account_id == source.api.currentLoggedIn.id }?.role != FMemberInfo.EPartyMemberRole.CAPTAIN) {
		throw SimpleCommandExceptionType(LiteralMessage("You are not the party leader.")).create()
	}
	party.members.firstOrNull { it.account_id == user.id } ?: throw SimpleCommandExceptionType(LiteralMessage("**%s** is not in your party.".format(user.displayName))).create()
	partyManager.kick(user.id)
	source.complete(null, source.createEmbed().setDescription("✅ Successfully kicked **%s**.".format(user.displayName)).build())
	return Command.SINGLE_SUCCESS
}

private fun partyPromote(source: CommandSourceStack, user: GameProfile): Int {
	source.ensureSession()
	source.loading("Getting party info")
	val partyManager = source.session.getPartyManager(source.api.currentLoggedIn.id)
	partyManager.fetchParty()
	val party = partyManager.partyInfo ?: throw SimpleCommandExceptionType(LiteralMessage("You are not in a party.")).create()
	if (party.members.firstOrNull{ it.account_id == source.api.currentLoggedIn.id }?.role != FMemberInfo.EPartyMemberRole.CAPTAIN) {
		throw SimpleCommandExceptionType(LiteralMessage("You are not the party leader.")).create()
	}
	party.members.firstOrNull { it.account_id == user.id } ?: throw SimpleCommandExceptionType(LiteralMessage("**%s** is not in your party.".format(user.displayName))).create()
	partyManager.promote(user.id)
	source.complete(null, source.createEmbed().setDescription("✅ **%s** is now the leader.".format(user.displayName)).build())
	return Command.SINGLE_SUCCESS
}

private fun partyLeave(source: CommandSourceStack): Int {
	source.ensureSession()
	source.loading("Getting party info")
	val partyManager = source.session.getPartyManager(source.api.currentLoggedIn.id)
	partyManager.fetchParty()
	if (partyManager.partyInfo == null) {
		throw SimpleCommandExceptionType(LiteralMessage("You are not in a party.")).create()
	}
	partyManager.kick(source.api.currentLoggedIn.id)
	source.complete(null, source.createEmbed().setDescription("✅ Successfully left the party.").build())
	return Command.SINGLE_SUCCESS
}