package com.caglar.sivilsavunma

data class QuizQuestion(
    val id: String,
    val number: Int,
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    val answerText: String,
    val explanation: String,
    val setTitle: String,
    val setID: String,
    val constitutionArticle: String,
    val constitutionFeedback: String
) {
    val correctAnswer: String
        get() = options.getOrNull(correctIndex) ?: answerText
}

enum class StudyCategory(val label: String, val iconText: String) {
    GENERAL("Genel Kültür", "★"),
    REAL("Gerçek Sınavlar", "✓"),
    GEOGRAPHY("Kıbrıs Coğrafyası", "⌖"),
    HISTORY("Kıbrıs Tarihi", "◷"),
    DISASTER("Afet Yönetimi", "◆")
}

data class QuizSet(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: StudyCategory,
    val questions: List<QuizQuestion>,
    val iconText: String
) {
    val scoreKey: String
        get() = questions.firstOrNull()?.setID ?: id
}

data class RuntimeQuestion(
    val source: QuizQuestion,
    val optionOrder: List<Int>,
    val eliminatedOriginalIndices: MutableSet<Int> = mutableSetOf()
) {
    val displayedCorrectIndex: Int
        get() = optionOrder.indexOf(source.correctIndex).takeIf { it >= 0 } ?: source.correctIndex
}

data class SavedQuestionState(
    val questionID: String,
    val optionOrder: List<Int>,
    val eliminated: List<Int>
)

data class SavedTest(
    val id: String,
    val setID: String,
    val title: String,
    val questions: List<SavedQuestionState>,
    val currentIndex: Int,
    val correct: Int,
    val wrong: Int,
    val currentSelectedDisplayIndex: Int?,
    val currentAnswered: Boolean,
    val scoreSetID: String?,
    val wrongReviewSetID: String?,
    val savedAt: Long
)

data class LastScore(
    val percent: Int,
    val correct: Int,
    val wrong: Int,
    val total: Int,
    val date: Long
)

data class AppStats(
    val answered: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0
)

data class QuizSession(
    val set: QuizSet,
    val runtimeQuestions: List<RuntimeQuestion>,
    var index: Int = 0,
    var correct: Int = 0,
    var wrong: Int = 0,
    var selectedDisplayIndex: Int? = null,
    var answered: Boolean = false,
    var savedID: String? = null,
    val scoreSetID: String? = null,
    val wrongReviewSetID: String? = null
) {
    val current: RuntimeQuestion
        get() = runtimeQuestions[index]
}
