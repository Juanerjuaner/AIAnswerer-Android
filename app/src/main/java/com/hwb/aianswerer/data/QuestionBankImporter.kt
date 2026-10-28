package com.hwb.aianswerer.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.hwb.aianswerer.MyApplication
import com.hwb.aianswerer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * 题库文件导入器
 *
 * 仅支持 .xlsx 与 .docx，使用系统自带 ZIP + XmlPullParser 解析，不引入 Apache POI。
 */
object QuestionBankImporter {

    private const val MAX_FILE_SIZE = 30L * 1024 * 1024

    private const val MIME_XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val MIME_DOCX =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** 文件选择器可接受的 MIME 类型 */
    val MIME_TYPES = arrayOf(MIME_XLSX, MIME_DOCX, "application/octet-stream", "application/zip")

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val context = MyApplication.getAppContext()
        val tempFile = copyToTempFile(context, uri)
        try {
            ZipFile(tempFile).use { zip ->
                val questions = when {
                    zip.getEntry("word/document.xml") != null -> parseDocx(zip)
                    zip.getEntry("xl/workbook.xml") != null -> parseXlsx(zip)
                    else -> throw IllegalArgumentException(
                        MyApplication.getString(R.string.question_bank_unsupported)
                    )
                }
                if (questions.isEmpty()) {
                    throw IllegalArgumentException(
                        MyApplication.getString(R.string.question_bank_parse_empty)
                    )
                }
                QuestionBankRepository.insertAll(questions)
            }
        } catch (e: ZipException) {
            throw IllegalArgumentException(
                MyApplication.getString(R.string.question_bank_unsupported)
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun copyToTempFile(context: Context, uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(
                MyApplication.getString(R.string.question_bank_unsupported)
            )

        val tempFile = File.createTempFile("question_bank_", ".tmp", context.cacheDir)
        try {
            input.use { source ->
                FileOutputStream(tempFile).use { target ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_FILE_SIZE) {
                            throw IllegalArgumentException(
                                MyApplication.getString(R.string.question_bank_file_too_large)
                            )
                        }
                        target.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
        return tempFile
    }

    // ========== XLSX ==========

    private fun parseXlsx(zip: ZipFile): List<BankQuestion> {
        val sharedStrings = parseSharedStrings(zip)
        val questions = mutableListOf<BankQuestion>()

        val enumeration = zip.entries()
        val sheets = generateSequence {
            if (enumeration.hasMoreElements()) enumeration.nextElement() else null
        }.filter { it.name.matches(SHEET_ENTRY_REGEX) }
            .sortedBy { sheetNumber(it.name) }
            .toList()

        sheets.forEach { entry ->
            val rows = zip.getInputStream(entry).use { parseSheet(it, sharedStrings) }
            questions += TableQuestionParser.parse(rows)
        }
        return questions
    }

    private fun parseSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val result = mutableListOf<String>()

        zip.getInputStream(entry).use { input ->
            val parser = newParser(input)
            var current: StringBuilder? = null
            var inPhonetic = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.localName()) {
                        "si" -> current = StringBuilder()
                        "rPh" -> inPhonetic = true
                        "t" -> if (!inPhonetic) {
                            current?.append(parser.nextText())
                        }
                    }

                    XmlPullParser.END_TAG -> when (parser.localName()) {
                        "si" -> {
                            current?.let { result.add(it.toString()) }
                            current = null
                        }

                        "rPh" -> inPhonetic = false
                    }
                }
                parser.next()
            }
        }
        return result
    }

    private fun parseSheet(input: InputStream, sharedStrings: List<String>): List<List<String>> {
        val parser = newParser(input)
        val rows = mutableListOf<List<String>>()

        var currentRow: MutableMap<Int, String>? = null
        var cellColumn = -1
        var lastColumn = -1
        var cellType: String? = null
        var cellValue: StringBuilder? = null
        var inCell = false
        var inValue = false
        var inInlineText = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "row" -> {
                        currentRow = mutableMapOf()
                        lastColumn = -1
                    }
                    "c" -> {
                        inCell = true
                        val reference = parser.getAttributeValue(null, "r")
                        cellColumn = columnIndex(reference) ?: (lastColumn + 1)
                        lastColumn = cellColumn
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = StringBuilder()
                        inValue = false
                    }

                    "v" -> if (inCell) inValue = true
                    "t" -> if (inCell) inInlineText = true
                }

                XmlPullParser.TEXT -> {
                    if (inCell && (inValue || inInlineText)) {
                        cellValue?.append(parser.text)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.localName()) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val row = currentRow
                        if (row != null && inCell && cellColumn >= 0) {
                            val raw = cellValue?.toString().orEmpty()
                            val value = if (cellType == "s") {
                                kindOfSharedString(raw, sharedStrings)
                            } else {
                                raw
                            }
                            row[cellColumn] = TextNormalizer.truncate(value.trim())
                        }
                        inCell = false
                        inValue = false
                        inInlineText = false
                        cellValue = null
                        cellType = null
                    }

                    "row" -> {
                        currentRow?.let { row ->
                            if (row.isNotEmpty()) {
                                val maxIndex = row.keys.maxOrNull() ?: -1
                                rows.add((0..maxIndex).map { row[it].orEmpty() })
                            }
                        }
                        currentRow = null
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun kindOfSharedString(raw: String, sharedStrings: List<String>): String {
        val index = raw.trim().toIntOrNull() ?: return ""
        return sharedStrings.getOrNull(index).orEmpty()
    }

    private fun columnIndex(reference: String?): Int? {
        if (reference.isNullOrEmpty()) return null
        var index = 0
        var hasLetter = false
        for (ch in reference) {
            val upper = ch.uppercaseChar()
            if (upper in 'A'..'Z') {
                index = index * 26 + (upper - 'A' + 1)
                hasLetter = true
            } else {
                break
            }
        }
        return if (hasLetter) index - 1 else null
    }

    private fun sheetNumber(name: String): Int {
        return Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    // ========== DOCX ==========

    private fun parseDocx(zip: ZipFile): List<BankQuestion> {
        val entry = zip.getEntry("word/document.xml") ?: return emptyList()
        val tableRows = mutableListOf<List<String>>()
        val paragraphs = mutableListOf<String>()

        zip.getInputStream(entry).use { input ->
            val parser = newParser(input)
            var tableDepth = 0
            var inParagraph = false
            var inText = false
            var paragraph = StringBuilder()
            var cell: StringBuilder? = null
            var row: MutableList<String>? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.localName()) {
                        "tbl" -> tableDepth++
                        "tr" -> if (tableDepth == 1) row = mutableListOf()
                        "tc" -> if (tableDepth == 1) cell = StringBuilder()
                        "p" -> {
                            inParagraph = true
                            paragraph = StringBuilder()
                        }

                        "t" -> if (inParagraph) inText = true
                        "tab" -> if (inParagraph) paragraph.append(' ')
                        "br", "cr" -> if (inParagraph) paragraph.append('\n')
                    }

                    XmlPullParser.TEXT -> {
                        if (inParagraph && inText) {
                            paragraph.append(parser.text)
                        }
                    }

                    XmlPullParser.END_TAG -> when (parser.localName()) {
                        "t" -> inText = false
                        "p" -> {
                            if (inParagraph) {
                                val text = paragraph.toString().trim()
                                if (tableDepth >= 1) {
                                    cell?.let { it.append(text).append('\n') }
                                } else {
                                    paragraphs.add(text)
                                }
                            }
                            inParagraph = false
                        }

                        "tc" -> if (tableDepth == 1) {
                            row?.add(cell?.toString()?.trim().orEmpty())
                            cell = null
                        }

                        "tr" -> if (tableDepth == 1) {
                            row?.let { if (it.any { cellText -> cellText.isNotBlank() }) tableRows.add(it) }
                            row = null
                        }

                        "tbl" -> tableDepth = (tableDepth - 1).coerceAtLeast(0)
                    }
                }
                parser.next()
            }
        }

        return TableQuestionParser.parse(tableRows) + parseParagraphQuestions(paragraphs)
    }

    private fun parseParagraphQuestions(paragraphs: List<String>): List<BankQuestion> {
        val lines = paragraphs.map { it.trim() }
        if (lines.none { it.isNotEmpty() }) {
            return emptyList()
        }

        val hasPrefix = lines.any { QUESTION_PREFIX.containsMatchIn(it) || ANSWER_PREFIX.containsMatchIn(it) }
        if (hasPrefix) {
            return parsePrefixedLines(lines)
        }

        return if (lines.any { it.isBlank() }) {
            splitBlocks(lines).mapNotNull { toQuestionFromBlock(it) }
        } else {
            parsePairs(lines.filter { it.isNotEmpty() })
        }
    }

    private fun parsePrefixedLines(lines: List<String>): List<BankQuestion> {
        val result = mutableListOf<BankQuestion>()
        var question: String? = null
        var answer = StringBuilder()
        var answerStarted = false
        val options = mutableListOf<String>()

        fun flush() {
            val questionText = question?.trim().orEmpty()
            val answerText = answer.toString().trim()
            if (questionText.isNotEmpty() && answerText.isNotEmpty()) {
                result.add(
                    BankQuestion(
                        question = TextNormalizer.truncate(questionText),
                        answer = TextNormalizer.truncate(answerText),
                        options = options.takeIf { it.isNotEmpty() }
                    )
                )
            }
            question = null
            answer = StringBuilder()
            answerStarted = false
            options.clear()
        }

        lines.forEach { line ->
            if (line.isBlank()) {
                return@forEach
            }
            when {
                QUESTION_PREFIX.containsMatchIn(line) -> {
                    flush()
                    question = line.replaceFirst(QUESTION_PREFIX, "").trim()
                }

                ANSWER_PREFIX.containsMatchIn(line) && question != null -> {
                    answerStarted = true
                    answer.append(line.replaceFirst(ANSWER_PREFIX, "").trim())
                }

                OPTION_PREFIX.containsMatchIn(line) && question != null -> {
                    options.add(line)
                }

                question != null -> {
                    val text = line.trim()
                    if (answerStarted) {
                        if (answer.isNotEmpty()) answer.append('\n')
                        answer.append(text)
                    } else {
                        question = question + "\n" + text
                    }
                }
            }
        }
        flush()
        return result
    }

    private fun splitBlocks(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        lines.forEach { line ->
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) {
            blocks.add(current)
        }
        return blocks
    }

    private fun toQuestionFromBlock(block: List<String>): BankQuestion? {
        if (block.isEmpty()) return null
        val questionLine = block.first()
        if (OPTION_PREFIX.containsMatchIn(questionLine)) return null

        val question = questionLine.replaceFirst(QUESTION_PREFIX, "").trim()
        val options = block.drop(1).filter { OPTION_PREFIX.containsMatchIn(it) }
        val answerLines = block.drop(1).filter { !OPTION_PREFIX.containsMatchIn(it) }
        val answer = answerLines.joinToString("\n")
            .replaceFirst(ANSWER_PREFIX, "")
            .trim()

        if (question.isEmpty() || answer.isEmpty()) return null
        return BankQuestion(
            question = TextNormalizer.truncate(question),
            answer = TextNormalizer.truncate(answer),
            options = options.takeIf { it.isNotEmpty() }
        )
    }

    private fun parsePairs(lines: List<String>): List<BankQuestion> {
        val result = mutableListOf<BankQuestion>()
        var index = 0
        while (index + 1 < lines.size) {
            val question = lines[index].replaceFirst(QUESTION_PREFIX, "").trim()
            val answer = lines[index + 1].replaceFirst(ANSWER_PREFIX, "").trim()
            if (question.isNotEmpty() && answer.isNotEmpty() &&
                !OPTION_PREFIX.containsMatchIn(question)
            ) {
                result.add(
                    BankQuestion(
                        question = TextNormalizer.truncate(question),
                        answer = TextNormalizer.truncate(answer)
                    )
                )
            }
            index += 2
        }
        return result
    }

    // ========== 公共解析 ==========

    private fun newParser(input: InputStream): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        return parser
    }

    private fun XmlPullParser.localName(): String = name.orEmpty().substringAfter(':')

    private val SHEET_ENTRY_REGEX = Regex("xl/worksheets/sheet\\d+\\.xml")

    private val QUESTION_PREFIX =
        Regex("^\\s*(题目|问题|题干|question)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val ANSWER_PREFIX =
        Regex(
            "^\\s*(答案解析|答案与解析|正确答案|参考答案|答案|解析|answer)\\s*[:：]?\\s*",
            RegexOption.IGNORE_CASE
        )
    private val OPTION_PREFIX = Regex("^\\s*[A-Ha-h][.、．)）]\\s*")

    /**
     * Excel sheet 与 Word 表格共用的表格解析
     */
    private object TableQuestionParser {

        private val questionAliases = listOf("题目", "问题", "题干", "试题", "question")
        private val answerAliases = listOf("正确答案", "参考答案", "答案", "解析", "answer")
        private val optionAliases = listOf("选项", "备选项", "options")
        private val typeAliases = listOf("题型", "类型", "type")

        private class ColumnMapping(
            val question: Int,
            val answer: Int,
            val options: Int?,
            val type: Int?
        )

        fun parse(rows: List<List<String>>): List<BankQuestion> {
            val nonEmptyRows = rows.filter { row -> row.any { it.isNotBlank() } }
            if (nonEmptyRows.isEmpty()) return emptyList()

            val headerIndex = nonEmptyRows.take(5).indexOfFirst { isHeader(it) }
            val mapping = if (headerIndex >= 0) resolveHeader(nonEmptyRows[headerIndex]) else null
            val dataRows = if (headerIndex >= 0) nonEmptyRows.drop(headerIndex + 1) else nonEmptyRows

            val questionIndex = mapping?.question ?: 0
            val answerIndex = mapping?.answer ?: 1
            val optionsIndex = mapping?.options ?: if (mapping == null) 2 else -1
            val typeIndex = mapping?.type ?: if (mapping == null) 3 else -1

            return dataRows.mapNotNull { row ->
                val question = row.getOrNull(questionIndex)?.trim().orEmpty()
                val answer = row.getOrNull(answerIndex)?.trim().orEmpty()
                if (question.isEmpty() || answer.isEmpty()) {
                    return@mapNotNull null
                }
                BankQuestion(
                    question = TextNormalizer.truncate(question),
                    answer = TextNormalizer.truncate(answer),
                    options = splitOptions(row.getOrNull(optionsIndex)),
                    questionType = row.getOrNull(typeIndex)?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
        }

        private fun isHeader(row: List<String>): Boolean {
            val hasQuestion = row.any { cell -> matches(cell, questionAliases) }
            val hasAnswer = row.any { cell -> matches(cell, answerAliases) }
            return hasQuestion && hasAnswer
        }

        private fun resolveHeader(row: List<String>): ColumnMapping? {
            val cells = row.map { it.trim().lowercase() }
            val used = mutableSetOf<Int>()

            // 先精确匹配、再包含匹配；答案/选项/题型优先，避免「题目类型」被误判为题干
            val answer = findColumn(cells, answerAliases, used)
            val options = findColumn(cells, optionAliases, used)
            val type = findColumn(cells, typeAliases, used)
            val question = findColumn(cells, questionAliases, used)

            if (question < 0 || answer < 0) return null
            return ColumnMapping(
                question = question,
                answer = answer,
                options = options.takeIf { it >= 0 },
                type = type.takeIf { it >= 0 }
            )
        }

        private fun findColumn(
            cells: List<String>,
            aliases: List<String>,
            used: MutableSet<Int>
        ): Int {
            aliases.forEach { alias ->
                val exact = cells.indexOfFirst { it == alias && it !in used }
                if (exact >= 0) {
                    used.add(exact)
                    return exact
                }
            }
            aliases.forEach { alias ->
                val partial = cells.indexOfFirst { it.contains(alias) && it !in used }
                if (partial >= 0) {
                    used.add(partial)
                    return partial
                }
            }
            return -1
        }

        private fun matches(cell: String, aliases: List<String>): Boolean {
            val value = cell.trim().lowercase()
            if (value.isEmpty()) return false
            return aliases.any { value.contains(it) }
        }

        private fun splitOptions(value: String?): List<String>? {
            if (value.isNullOrBlank()) return null
            val options = value.split('\n', '|', '；', ';')
                .map { TextNormalizer.truncate(it.trim()) }
                .filter { it.isNotEmpty() }
            return options.takeIf { it.isNotEmpty() }
        }
    }
}
