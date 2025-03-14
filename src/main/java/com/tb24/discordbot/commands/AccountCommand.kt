package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.util.*
import com.tb24.fn.model.account.AccountMutationPayload
import com.tb24.fn.model.account.BackupCodesResponse
import com.tb24.fn.util.EAuthClient
import com.tb24.fn.util.Formatters

class AccountCommand : BrigadierCommand("account", "Account commands.", arrayOf("a")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::displaySummary)
		.then(literal("displayname")
			.then(argument("new name", greedyString())
				.executes { setDisplayName(it.source, getString(it, "new name")) }
			)
		)
		.then(literal("password")
			.executes(::changePasswordFlow)
		)
		.then(literal("backupcodes")
			.executes(::displayBackupCodes)
			.then(literal("generate")
				.executes(::generateBackupCodes)
			)
		)
		.then(literal("unlink")
			.then(argument("external auth type", greedyString())
				.executes { unlink(it.source, getString(it, "external auth type").toLowerCase()) }
			)
		)

	private inline fun displaySummary(c: CommandContext<CommandSourceStack>): Int {
		val source = c.source
		source.ensurePremium("View more account info")
		source.ensureSession()
		source.api.accountService.verify(null).exec()
		if (!source.unattended && !source.complete(null, source.createEmbed().setColor(COLOR_WARNING)
				.setTitle("✋ Hold up!")
				.setDescription("You're about to view the account details of ${source.api.currentLoggedIn.displayName}. Some of the data that we will send here might be sensitive, such as real name or Facebook name. We don't recommend to proceed if this account isn't yours.\n\nContinue?")
				.build(), confirmationButtons()).awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		if (!source.unattended) {
			source.loading("Getting account data")
		}
		//val avatarKeys = source.session.channelsManager.getUserSettings(source.api.currentLoggedIn.id, ChannelsManager.KEY_AVATAR, ChannelsManager.KEY_AVATAR_BACKGROUND)
		val data = source.api.accountService.getById(source.api.currentLoggedIn.id).exec().body()!!
		val embed = source.createEmbed()
			.setTitle("Epic Account Summary")
			.addField("Account ID", data.id, false)
			.addField("Name", "||${data.name} ${data.lastName}||", true)
			.addField("Email", "||${data.email}||${if (data.emailVerified) " ✅" else " (unverified)"}", true)
			.addField("Display Name", StringBuilder("Current: ${data.epicDisplayName.orDash()}")
				.append("\nChanges: ${Formatters.num.format(data.numberOfDisplayNameChanges)}")
				.append("\nLast changed: ${data.lastDisplayNameChange?.run { renderWithRelative() } ?: "\u2014"}")
				.apply {
					if (data.canUpdateDisplayName != true) {
						append("\nNext change: ${data.canUpdateDisplayNameNext?.run { renderWithRelative() } ?: "\u2014"}")
					}
				}.toString(), false)
			.addField("Linked Accounts", source.api.accountService.getExternalAuths(source.api.currentLoggedIn.id).exec().body()!!.takeIf { it.isNotEmpty() }?.joinToString("\n\n") {
				"__${it.type}: ||**${it.externalDisplayName.orDash()}**||__\nAdded: ${it.dateAdded?.run { renderWithRelative() } ?: "\u2014"}"
			} ?: "No linked accounts", false)
		if (source.unattended) {
			source.complete(null, embed.build())
			return Command.SINGLE_SUCCESS
		}
		val message = source.complete(null, embed.setFooter("React with anything within 2 minutes to remove this embed").build())
		if (message.awaitReactions({ _, _, _ -> true }, AwaitReactionsOptions().apply {
				max = 1
				time = 120000L
			}).await().isNotEmpty()) {
			message.delete().complete()
		}
		return Command.SINGLE_SUCCESS
	}

	private fun setDisplayName(source: CommandSourceStack, newName: String): Int {
		source.ensurePremium("Change account display name")
		source.ensureSession()
		if (newName.length < 3 || newName.length > 16) {
			throw SimpleCommandExceptionType(LiteralMessage("Display name must be between 3 and 16 characters long.")).create()
		}
		source.loading("Checking `$newName` for validity")
		runCatching { source.api.accountService.getByDisplayName(newName).exec() }.getOrNull()?.body()?.apply {
			throw SimpleCommandExceptionType(LiteralMessage("The Epic display name `$displayName` has already been taken. Please choose another name.")).create()
		}
		val oldName = source.api.currentLoggedIn.epicDisplayName.orDash()
		if (!source.complete(null, source.createEmbed().setColor(COLOR_WARNING)
				.setTitle("Change display name?")
				.setDescription("You're about to change the display name of account `${source.api.currentLoggedIn.id}`:\n\n`${oldName.orDash()}` \u2192 `$newName`\n\nThis action will be recorded in the Account History as `HISTORY_ACCOUNT_UPDATE`. You will not be able to change the display name again for the next 14 days if you proceed. Continue?")
				.build(), confirmationButtons()).awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.loading("Changing display name")
		val cnApi = source.session.getApiForOtherClient(EAuthClient.FORTNITE_CN_GAME_CLIENT)
		val response = cnApi.accountService.editAccountDetails(cnApi.currentLoggedIn.id, AccountMutationPayload().apply {
			displayName = newName
		}).exec().body()!!
		source.session.handleAccountMutation(response)
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setTitle("✅ Updated the Epic display name")
			.addField("Old name", oldName.orDash(), true)
			.addField("New name", response.accountInfo.epicDisplayName.orDash(), true)
			.setFooter("You can change your name again on")
			.setTimestamp(response.accountInfo.canUpdateDisplayNameNext.toInstant())
			.build())
		return Command.SINGLE_SUCCESS
	}

	private inline fun changePasswordFlow(c: CommandContext<CommandSourceStack>): Int {
		// PLEASE don't let me do this for real
		throw SimpleCommandExceptionType(LiteralMessage("oh, so you want to change your password through the bot directly? well it is illegal so go to the account page yourself.")).create()
	}

	private inline fun displayBackupCodes(c: CommandContext<CommandSourceStack>): Int {
		val source = c.source
		source.ensurePremium("View account's existing backup codes")
		source.ensureSession()
		source.loading("Getting backup codes")
		val response = source.api.accountService.getBackupCodes(source.api.currentLoggedIn.id).exec().body()!!
		val unusedCodes = response.backupCodes.filter { !it.used }
		source.complete(null, source.createEmbed()
			.setTitle("Backup Codes")
			.setDescription("${Formatters.num.format(unusedCodes.size)}/${Formatters.num.format(response.backupCodes.size)} available for use")
			.addField("Available", renderBackupCodes(unusedCodes).takeIf { it.isNotEmpty() } ?: "No available codes", false)
			.addField("Used", renderBackupCodes(response.backupCodes.filter { it.used }).takeIf { it.isNotEmpty() } ?: "No used codes", false)
			.setFooter("Generated at")
			.setTimestamp(response.generatedAt.toInstant())
			.build())
		return Command.SINGLE_SUCCESS
	}

	private inline fun generateBackupCodes(c: CommandContext<CommandSourceStack>): Int {
		val source = c.source
		source.ensurePremium("Generate new backup codes")
		source.ensureSession()
		source.loading("Generating backup codes")
		val response = source.api.accountService.generateBackupCodes(source.api.currentLoggedIn.id).exec().body()!!
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setTitle("✅ Generated new backup codes")
			.addField("Backup codes", renderBackupCodes(response.backupCodes.toList()), true)
			.build())
		return Command.SINGLE_SUCCESS
	}

	private fun renderBackupCodes(backupCodes: Collection<BackupCodesResponse.BackupCode>, columnSize: Int = 3) = StringBuilder().apply {
		backupCodes.forEachIndexed { i, it ->
			if (it.used) {
				append("~~`").append(it.code).append("`~~")
			} else {
				append('`').append(it.code).append('`')
			}
			if (i < backupCodes.size - 1) {
				append(if (i % columnSize == columnSize - 1) '\n' else ' ')
			}
		}
	}.toString()

	private inline fun unlink(source: CommandSourceStack, externalAuthType: String): Int {
		source.ensurePremium("Unlink a connected account")
		source.ensureSession()
		source.loading("Getting linked accounts")
		val externalAuth = runCatching { source.api.accountService.getExternalAuth(source.api.currentLoggedIn.id, externalAuthType).exec() }.getOrNull()?.body()
			?: throw SimpleCommandExceptionType(LiteralMessage("You don't have $externalAuthType linked.")).create()
		val consoleWarning = when (externalAuth.type) {
			"psn", "xbl", "nintendo" -> "Because the account you're about to unlink is a console account, **you won't be able to link another external account for the lifetime of this Epic account**. However, you can link the same external account again to this Epic account if you wish." + ' '
			else -> ""
		}
		if (!source.complete(null, source.createEmbed()
				.setTitle("Unlink $externalAuthType?")
				.setDescription("You're about to unlink a linked account with the following details:\n\n**Name**: ${externalAuth.externalDisplayName.orDash()}\n**ID(s)**:\n${externalAuth.authIds.joinToString("\n") { "\u2022 ${it.type}: ${it.id}" }}\n**Added**: ${externalAuth.dateAdded.renderWithRelative()}\n\nThis action will be recorded in the Account History as `HISTORY_ACCOUNT_EXTERNAL_AUTH_REMOVE`.\n\n${consoleWarning}Continue?")
				.build(), confirmationButtons()).awaitConfirmation(source.author).await()) {
			source.complete("👌 Alright.")
			return Command.SINGLE_SUCCESS
		}
		source.loading("Unlinking $externalAuthType")
		source.api.accountService.removeExternalAuth(source.api.currentLoggedIn.id, externalAuthType).exec()
		source.complete(null, source.createEmbed().setColor(COLOR_SUCCESS)
			.setTitle("✅ Unlinked $externalAuthType")
			.build())
		return Command.SINGLE_SUCCESS
	}
}