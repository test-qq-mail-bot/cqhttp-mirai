/*
 *
 * Part of codes was taken from Mirai Native
 *
 * Copyright (C) 2020 iTX Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * @author PeratX
 * @website https://github.com/iTXTech/mirai-native
 *
 */
package tech.mihoyo.mirai.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.getGroupOrNull
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.uploadImage
import net.mamoe.mirai.utils.MiraiExperimentalAPI
import net.mamoe.mirai.utils.currentTimeMillis
import tech.mihoyo.mirai.PluginBase
import tech.mihoyo.mirai.PluginBase.saveImageAsync
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.*
import kotlin.collections.HashMap

suspend fun cqMessageToMessageChains(
    bot: Bot,
    contact: Contact?,
    cqMessage: Any?,
    raw: Boolean = false
): MessageChain? {
    return when (cqMessage) {
        is String -> {
            return if (raw) {
                PlainText(cqMessage).asMessageChain()
            } else {
                codeToChain(bot, cqMessage, contact)
            }
        }
        is JsonArray -> {
            var messageChain = buildMessageChain { }
            for (msg in cqMessage) {
                try {
                    val data = msg.jsonObject["data"]
                    when (msg.jsonObject["type"]?.jsonPrimitive?.content) {
                        "text" -> messageChain += PlainText(data!!.jsonObject["text"]!!.jsonPrimitive.content)
                        else -> messageChain += cqTextToMessageInternal(bot, contact, msg)
                    }
                } catch (e: NullPointerException) {
                    logger.warning("Got null when parsing CQ message array")
                    continue
                }
            }
            return messageChain
        }
        is JsonObject -> {
            return try {
                val data = cqMessage.jsonObject["data"]
                when (cqMessage.jsonObject["type"]?.jsonPrimitive?.content) {
                    "text" -> PlainText(data!!.jsonObject["text"]!!.jsonPrimitive.content).asMessageChain()
                    else -> cqTextToMessageInternal(bot, contact, cqMessage).asMessageChain()
                }
            } catch (e: NullPointerException) {
                logger.warning("Got null when parsing CQ message object")
                null
            }
        }
        is JsonPrimitive -> {
            return if (raw) {
                PlainText(cqMessage.content).asMessageChain()
            } else {
                codeToChain(bot, cqMessage.content, contact)
            }
        }
        else -> {
            logger.warning("Cannot determine type of " + cqMessage.toString())
            return null
        }
    }
}


private suspend fun cqTextToMessageInternal(bot: Bot, contact: Contact?, message: Any): Message {
    return when (message) {
        is String -> {
            if (message.startsWith("[CQ:") && message.endsWith("]")) {
                val parts = message.substring(4, message.length - 1).split(delimiters = *arrayOf(","), limit = 2)

                lateinit var args: HashMap<String, String>
                args = if (parts.size == 2) {
                    parts[1].toMap()
                } else {
                    HashMap()
                }
                return convertToMiraiMessage(bot, contact, parts[0], args)
            }
            return PlainText(message.unescape())
        }
        is JsonObject -> {
            val type = message.jsonObject["type"]!!.jsonPrimitive.content
            val data = message.jsonObject["data"] ?: return MSG_EMPTY
            val args = data.jsonObject.keys.map { it to data.jsonObject[it]!!.jsonPrimitive.content }.toMap()
            return convertToMiraiMessage(bot, contact, type, args)
        }
        else -> MSG_EMPTY
    }
}

private suspend fun convertToMiraiMessage(
    bot: Bot,
    contact: Contact?,
    type: String,
    args: Map<String, String>
): Message {
    when (type) {
        "at" -> {
            if (args["qq"] == "all") {
                return AtAll
            } else {
                val group = bot.getGroupOrNull(contact!!.id) ?: return MSG_EMPTY
                val member = group.getOrNull(args["qq"]!!.toLong()) ?: return MSG_EMPTY
                return At(member)
            }
        }
        "face" -> {
            return Face(args["id"]!!.toInt())
        }
        "emoji" -> {
            return PlainText(String(Character.toChars(args["id"]!!.toInt())))
        }
        "image" -> {
            return tryResolveMedia("image", contact, args)
        }
        "share" -> {
            return RichMessageHelper.share(
                args["url"]!!,
                args["title"],
                args["content"],
                args["image"]
            )
        }
        "contact" -> {
            return if (args["type"] == "qq") {
                RichMessageHelper.contactQQ(bot, args["id"]!!.toLong())
            } else {
                RichMessageHelper.contactGroup(bot, args["id"]!!.toLong())
            }
        }
        "music" -> {
            when (args["type"]) {
                "qq" -> return QQMusic.send(args["id"]!!)
                "163" -> return NeteaseMusic.send(args["id"]!!)
                "custom" -> return Music.custom(
                    args["url"]!!,
                    args["audio"]!!,
                    args["title"]!!,
                    args["content"],
                    args["image"]
                )
            }
        }
        "shake" -> {
            return PokeMessage.Poke
        }
        "poke" -> {
            PokeMessage.values.forEach {
                if (it.type == args["type"]!!.toInt() && it.id == args["id"]!!.toInt()) {
                    return it
                }
            }
            return MSG_EMPTY
        }
        "xml" -> {
            return XmlMessage(args["data"]!!)
        }
        "json" -> {
            return JsonMessage(args["data"]!!)
        }
        else -> {
            logger.debug("不支持的 CQ码：${type}")
        }
    }
    return MSG_EMPTY
}


private val MSG_EMPTY = PlainText("")

private fun String.escape(): String {
    return replace("&", "&amp;")
        .replace("[", "&#91;")
        .replace("]", "&#93;")
        .replace(",", "&#44;")
}

private fun String.unescape(): String {
    return replace("&amp;", "&")
        .replace("&#91;", "[")
        .replace("&#93;", "]")
        .replace("&#44;", ",")
}

private fun String.toMap(): HashMap<String, String> {
    val map = HashMap<String, String>()
    split(",").forEach {
        val parts = it.split(delimiters = *arrayOf("="), limit = 2)
        map[parts[0].trim()] = parts[1].unescape()
    }
    return map
}

@MiraiExperimentalAPI
suspend fun Message.toCQString(): String {
    return when (this) {
        is PlainText -> content.escape()
        is At -> "[CQ:at,qq=$target]"
        is Face -> "[CQ:face,id=$id]"
        is VipFace -> "[CQ:vipface,id=${kind.id},name=${kind.name},count=${count}]"
        is PokeMessage -> "[CQ:poke,id=${id},type=${type},name=${name}]"
        is AtAll -> "[CQ:at,qq=all]"
        is Image -> "[CQ:image,file=${md5.toUHexString("")},url=${queryUrl().escape()}]"
        is RichMessage -> "[CQ:rich,data=${content.escape()}]"
        is MessageSource -> ""
        is QuoteReply -> ""
        is Voice -> "[CQ:record,url=${url.escape()},file=${md5.toUHexString("")}]"
        else -> "此处消息的转义尚未被插件支持"
    }
}

suspend fun codeToChain(bot: Bot, message: String, contact: Contact?): MessageChain {
    return buildMessageChain {
        if (message.contains("[CQ:")) {
            var interpreting = false
            val sb = StringBuilder()
            var index = 0
            message.forEach { c: Char ->
                if (c == '[') {
                    if (interpreting) {
                        logger.error("CQ消息解析失败：$message，索引：$index")
                        return@forEach
                    } else {
                        interpreting = true
                        if (sb.isNotEmpty()) {
                            val lastMsg = sb.toString()
                            sb.delete(0, sb.length)
                            +cqTextToMessageInternal(bot, contact, lastMsg)
                        }
                        sb.append(c)
                    }
                } else if (c == ']') {
                    if (!interpreting) {
                        logger.error("CQ消息解析失败：$message，索引：$index")
                        return@forEach
                    } else {
                        interpreting = false
                        sb.append(c)
                        if (sb.isNotEmpty()) {
                            val lastMsg = sb.toString()
                            sb.delete(0, sb.length)
                            +cqTextToMessageInternal(bot, contact, lastMsg)
                        }
                    }
                } else {
                    sb.append(c)
                }
                index++
            }
            if (sb.isNotEmpty()) {
                +cqTextToMessageInternal(bot, contact, sb.toString())
            }
        } else {
            +PlainText(message.unescape())
        }
    }
}

fun getDataFile(type: String, name: String): File? {
    arrayOf(
        File(PluginBase.dataFolder, type).absolutePath + File.separatorChar,
        "data" + File.separatorChar + type + File.separatorChar,
        System.getProperty("java.library.path")
            .substringBefore(";") + File.separatorChar + "data" + File.separatorChar + type + File.separatorChar,
        ""
    ).forEach {
        val f = File(it + name).absoluteFile
        if (f.exists()) {
            return f
        }
    }
    return null
}

suspend fun tryResolveMedia(type: String, contact: Contact?, args: Map<String, String>): Message {
    var media: Message? = null
    var mediaBytes: ByteArray? = null
    var mediaUrl: String? = null

    withContext(Dispatchers.IO) {
        if (args.containsKey("file")) {
            with(args["file"]!!) {
                when {
                    startsWith("base64://") -> {
                        mediaBytes = Base64.getDecoder().decode(args["file"]!!.replace("base64://", ""))
                    }
                    startsWith("http") -> {
                        mediaUrl = args["file"]
                    }
                    else -> {
                        val filePath = args["file"]!!
                        if (filePath.startsWith("file:///")) {
                            var fileUri = URL(args["file"]).toURI()
                            if (fileUri.authority != null && fileUri.authority.isNotEmpty()) {
                                fileUri = URL("file://" + args["file"]!!.substring("file:".length)).toURI()
                            }
                            val file = File(fileUri).absoluteFile
                            if (file.exists() && file.canRead()) {
                                mediaBytes = file.readBytes()
                            }
                        } else {
                            if (type == "image") {
                                media = tryResolveCachedImage(filePath, contact)
                            }
                            if (media == null) {
                                val file = getDataFile(type, filePath)
                                if (file != null && file.canRead()) {
                                    mediaBytes = file.readBytes()
                                }
                            }
                        }
                        if (mediaBytes == null) {
                            if (args.containsKey("url")) {
                                mediaUrl = args["url"]!!
                            }
                        }
                    }
                }
            }
        } else if (args.containsKey("url")) {
            mediaUrl = args["url"]!!
        }

        if (mediaBytes == null && mediaUrl != null) {
            var useCache = true
            if (args.containsKey("cache")) {
                try {
                    useCache = args["cache"]?.toIntOrNull() != 0
                } catch (e: Exception) {
                    logger.debug(e.message)
                }
            }

            val urlHash = md5(mediaUrl!!).toUHexString("")

            when (type) {
                "image" -> {
                    if (useCache) {
                        media = tryResolveCachedImage(urlHash, contact)
                    }

                    if (media == null || !useCache) {
                        mediaBytes = HttpClient.getBytes(mediaUrl!!)

                        val bis = ByteArrayInputStream(mediaBytes)
                        media = contact!!.uploadImage(bis)

                        if (useCache) {
                            val imageMD5 = mediaBytes?.let { md5(it) }?.toUHexString("")
                            if (imageMD5 != null) {
                                val cqImgContent = """
                                    [image]
                                    md5=$imageMD5
                                    size=${mediaBytes?.size ?: 0}
                                    url=https://gchat.qpic.cn/gchatpic_new/${contact.bot.id}/0-00-$imageMD5/0?term=2
                                    addtime=$currentTimeMillis
                                    """.trimIndent()
                                logger.info("此链接图片将缓存为$urlHash.cqimg")
                                saveImageAsync("$urlHash.cqimg", cqImgContent).start()
                            }
                        }
                    }
                }
            }
        }
    }

    if (media != null) {
        return media as Message
    } else if (mediaBytes != null) {
        when (type) {
            "image" -> {
                val bis = ByteArrayInputStream(mediaBytes)
                media = withContext(Dispatchers.IO) { contact!!.uploadImage(bis) }
                return media as Image
            }
        }
    }
    return PlainText("插件无法获取到图片" + if (mediaUrl != null) ", 原图链接: $mediaUrl" else "")
}

suspend fun tryResolveCachedImage(name: String, contact: Contact?): Image? {
    var image: Image? = null
    val cacheFile = getDataFile("image", "$name.cqimg")
    if (cacheFile != null) {
        if (cacheFile.canRead()) {
            logger.info("此链接图片已缓存, 如需删除缓存请至 ${cacheFile.absolutePath}")
            var md5 = ""
            var size = 0
            var addTime = 0L

            val cacheMediaContent = cacheFile.readLines()
            cacheMediaContent.forEach {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    when (parts[0]) {
                        "md5" -> md5 = parts[1]
                        "size" -> size = parts[1].toIntOrNull() ?: 0
                        "addtime" -> addTime = parts[1].toLongOrNull() ?: 0L
                    }
                }
            }

            if (md5 != "" && size != 0) {
                if (contact != null) {
                    // If add time till now more than one day, check if the image exists
                    if (addTime - currentTimeMillis >= 1000 * 60 * 60 * 24) {
                        if (ImgUtil.tryGroupPicUp(
                                contact.bot,
                                contact.id,
                                md5,
                                size
                            ) != ImgUtil.ImageState.FileExist
                        ) {
                            cacheFile.delete()
                        } else { // If file exists
                            image = Image(ImgUtil.md5ToImageId(md5, contact))
                            val cqImgContent = """
                                                [image]
                                                md5=$md5
                                                size=$size
                                                url=https://gchat.qpic.cn/gchatpic_new/${contact.bot.id}/0-00-$md5/0?term=2
                                                addtime=$currentTimeMillis
                                            """.trimIndent()
                            saveImageAsync("$name.cqimg", cqImgContent).start() // Update cache file
                        }
                    } else { // If time < one day
                        image = Image(ImgUtil.md5ToImageId(md5, contact))
                    }
                }
            } else { // If cache file corrupted
                cacheFile.delete()
            }
        } else {
            logger.error("Image $name cache file cannot read.")
        }
    }
    return image
}

fun md5(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("MD5").digest(data)
}

fun md5(str: String): ByteArray = md5(str.toByteArray())

@ExperimentalUnsignedTypes
internal fun ByteArray.toUHexString(
    separator: String = " ",
    offset: Int = 0,
    length: Int = this.size - offset
): String {
    if (length == 0) {
        return ""
    }
    val lastIndex = offset + length
    return buildString(length * 2) {
        this@toUHexString.forEachIndexed { index, it ->
            if (index in offset until lastIndex) {
                var ret = it.toUByte().toString(16).toUpperCase()
                if (ret.length == 1) ret = "0$ret"
                append(ret)
                if (index < lastIndex - 1) append(separator)
            }
        }
    }
}