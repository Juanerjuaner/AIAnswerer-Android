package com.hwb.aianswerer.data

/**
 * 本地题库中的一道题
 */
data class BankQuestion(
    val question: String,
    val answer: String,
    val options: List<String>? = null,
    val questionType: String? = null
)

/**
 * 导入结果
 *
 * @param total 解析出的题目总数
 * @param added 实际写入题库的题目数
 */
data class ImportResult(
    val total: Int,
    val added: Int
) {
    val skipped: Int get() = total - added
}

/**
 * 题干归一化工具
 *
 * 用于本地题库匹配：去掉空格、标点、全半角差异和大小写差异，
 * 只保留字母与数字（汉字属于字母，会被保留）。
 */
object TextNormalizer {

    private const val MAX_TEXT_LENGTH = 10_000

    fun normalize(raw: String): String {
        val length = minOf(raw.length, MAX_TEXT_LENGTH)
        val builder = StringBuilder(length)
        for (i in 0 until length) {
            val ch = raw[i]
            val halfWidth = if (ch.code in 0xFF01..0xFF5E) {
                (ch.code - 0xFEE0).toChar()
            } else {
                ch
            }
            if (halfWidth.isLetterOrDigit()) {
                builder.append(halfWidth.lowercaseChar())
            }
        }
        return builder.toString()
    }

    fun truncate(raw: String): String {
        return if (raw.length > MAX_TEXT_LENGTH) raw.substring(0, MAX_TEXT_LENGTH) else raw
    }
}
