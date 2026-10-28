package com.hwb.aianswerer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.hwb.aianswerer.MyApplication
import com.hwb.aianswerer.models.AIAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地题库数据库
 */
class QuestionBankDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_QUESTIONS(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                question TEXT NOT NULL,
                normalized TEXT NOT NULL,
                answer TEXT NOT NULL,
                options_json TEXT,
                question_type TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX idx_questions_normalized ON $TABLE_QUESTIONS(normalized)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_QUESTIONS")
        onCreate(db)
    }

    companion object {
        const val DATABASE_NAME = "question_bank.db"
        const val DATABASE_VERSION = 1
        const val TABLE_QUESTIONS = "questions"
    }
}

/**
 * 本地题库仓库
 *
 * 题目以归一化后的题干作为唯一键，重复导入不会产生重复记录。
 */
object QuestionBankRepository {

    /** 过短的题干容易误命中通用短句，不参与匹配 */
    private const val MIN_QUESTION_LEN = 8

    private val gson = Gson()

    @Volatile
    private var database: QuestionBankDatabase? = null

    private fun db(): QuestionBankDatabase {
        return database ?: synchronized(this) {
            database ?: QuestionBankDatabase(MyApplication.getAppContext()).also {
                database = it
            }
        }
    }

    /**
     * 在题库中查找与识别文本最匹配的一道题。
     *
     * 匹配规则：归一化后，题库题干被完整包含在识别文本中；取最长的一条。
     */
    suspend fun findMatch(text: String): AIAnswer? = withContext(Dispatchers.IO) {
        val normalized = TextNormalizer.normalize(text)
        if (normalized.length < MIN_QUESTION_LEN) {
            return@withContext null
        }

        db().readableDatabase.rawQuery(
            """
            SELECT question, answer, options_json, question_type
            FROM ${QuestionBankDatabase.TABLE_QUESTIONS}
            WHERE length(normalized) >= ? AND instr(?, normalized) > 0
            ORDER BY length(normalized) DESC, id ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(MIN_QUESTION_LEN.toString(), normalized)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return@withContext null
            }
            val optionsJson = if (cursor.isNull(2)) null else cursor.getString(2)
            AIAnswer(
                question = cursor.getString(0),
                questionType = cursor.getString(3) ?: "",
                answer = cursor.getString(1),
                options = parseOptions(optionsJson)
            )
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        db().readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${QuestionBankDatabase.TABLE_QUESTIONS}",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * 累加导入题目，按归一化题干去重
     */
    suspend fun insertAll(questions: List<BankQuestion>): ImportResult =
        withContext(Dispatchers.IO) {
            val writableDb = db().writableDatabase
            var added = 0

            writableDb.beginTransaction()
            try {
                questions.forEach { question ->
                    val normalized = TextNormalizer.normalize(question.question)
                    if (normalized.length < MIN_QUESTION_LEN) {
                        return@forEach
                    }

                    val values = ContentValues().apply {
                        put("question", question.question)
                        put("normalized", normalized)
                        put("answer", question.answer)
                        put(
                            "options_json",
                            question.options?.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
                        )
                        put("question_type", question.questionType)
                    }

                    val rowId = writableDb.insertWithOnConflict(
                        QuestionBankDatabase.TABLE_QUESTIONS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                    if (rowId != -1L) {
                        added++
                    }
                }
                writableDb.setTransactionSuccessful()
            } finally {
                writableDb.endTransaction()
            }

            ImportResult(total = questions.size, added = added)
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db().writableDatabase.delete(QuestionBankDatabase.TABLE_QUESTIONS, null, null)
        Unit
    }

    private fun parseOptions(json: String?): List<String>? {
        if (json.isNullOrBlank()) {
            return null
        }
        return try {
            gson.fromJson(json, Array<String>::class.java)?.toList()
        } catch (e: Exception) {
            null
        }
    }
}
