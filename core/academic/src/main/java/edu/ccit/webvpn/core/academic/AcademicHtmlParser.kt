package edu.ccit.webvpn.core.academic

internal object AcademicHtmlParser {
    private val selectRegex = Regex(
        """<select\b(?=[^>]*\bname\s*=\s*["']kksj["'])[^>]*>(.*?)</select>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val optionRegex = Regex(
        """<option\b([^>]*)>(.*?)</option>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val valueRegex = Regex(
        """\bvalue\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val gradesTableRegex = Regex(
        """<table\b(?=[^>]*\bid\s*=\s*["']dataList["'])[^>]*>(.*?)</table>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val rowRegex = Regex(
        """<tr\b[^>]*>(.*?)</tr>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val cellRegex = Regex(
        """<td\b[^>]*>(.*?)</td>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tagRegex = Regex("<[^>]+>")
    private val numericEntityRegex = Regex("&#(x?[0-9a-fA-F]+);")

    private val timetableTableRegex = Regex(
        """<table\b(?=[^>]*\bid\s*=\s*["']kbtable["'])[^>]*>(.*?)</table>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tableCellRegex = Regex(
        """<t[dh]\b[^>]*>(.*?)</t[dh]>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val divRegex = Regex(
        """<div\b([^>]*)>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val classRegex = Regex("""\bclass\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val titledFontRegex = Regex(
        """<font\b(?=[^>]*\btitle\s*=\s*["']([^"']*)["'])[^>]*>(.*?)</font>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parseTerms(html: String): List<AcademicTerm> {
        val select = selectRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
        return optionRegex.findAll(select).mapNotNull { match ->
            val value = valueRegex.find(match.groupValues[1])?.groupValues?.get(1).orEmpty()
            val label = plainText(match.groupValues[2])
            if (label.isBlank()) return@mapNotNull null
            AcademicTerm(
                value = decode(value),
                label = label,
                selected = match.groupValues[1].contains("selected", ignoreCase = true),
            )
        }.toList()
    }

    fun parseGrades(html: String): List<CourseGrade> {
        val table = gradesTableRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
        return rowRegex.findAll(table).mapNotNull { row ->
            val cells = cellRegex.findAll(row.groupValues[1])
                .map { plainText(it.groupValues[1]) }
                .toList()
            if (cells.size < GradeColumnCount || cells.firstOrNull() == "序号") {
                return@mapNotNull null
            }
            CourseGrade(
                sequence = cells[0],
                semester = cells[1],
                courseCode = cells[2],
                courseName = cells[3],
                groupName = cells[4],
                score = cells[5],
                scoreMark = cells[6],
                credit = cells[7],
                totalHours = cells[8],
                gradePoint = cells[9],
                generalElective = cells[10],
                originalScore = cells[11],
                description = cells[12],
                note = cells[13],
                retakeSemester = cells[14],
                assessmentMethod = cells[15],
                examType = cells[16],
                courseAttribute = cells[17],
                courseNature = cells[18],
                courseCategory = cells[19],
            )
        }.toList()
    }

    fun parseCourseSelectionOverview(html: String): CourseSelectionOverview {
        val terms = parseSelectOptions(html, "xnxqid")
        return CourseSelectionOverview(
            terms = terms,
            defaultTerm = terms.firstOrNull(AcademicTerm::selected)?.value
                ?: terms.firstOrNull { it.value.isNotBlank() }?.value.orEmpty(),
        )
    }

    fun parseSelectedCourses(html: String): List<SelectedCourse> = tableRows(html)
        .mapNotNull { row ->
            val cells = rowCells(row)
            if (cells.size < SelectionResultColumnCount || cells.firstOrNull() == "序号") {
                return@mapNotNull null
            }
            SelectedCourse(
                sequence = cells[0],
                courseName = cells[1],
                courseCode = cells[2],
                teacher = cells[3],
                totalHours = cells[4],
                credit = cells[5],
                courseAttribute = cells[6],
                courseNature = cells[7],
            )
        }.toList()

    fun parseEvaluationBatches(html: String): List<EvaluationBatch> {
        val visibleHtml = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return tableRows(visibleHtml).mapNotNull { row ->
            val cells = rowCells(row)
            if (cells.size < EvaluationBatchColumnCount || cells.firstOrNull() == "序号") {
                return@mapNotNull null
            }
            val path = firstLink(row) ?: return@mapNotNull null
            EvaluationBatch(
                sequence = cells[0],
                semester = cells[1],
                category = cells[2],
                name = cells[3],
                startDate = cells[4],
                endDate = cells[5],
                courseListPath = path,
            )
        }.toList()
    }

    fun parseEvaluationCourses(html: String): List<EvaluationCourse> {
        val table = gradesTableRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
        return rowRegex.findAll(table).mapNotNull { match ->
            val row = match.groupValues[1]
            val cells = rowCells(row)
            if (cells.size < EvaluationCourseColumnCount || cells.firstOrNull() == "序号") {
                return@mapNotNull null
            }
            val path = firstLink(row) ?: return@mapNotNull null
            EvaluationCourse(
                sequence = cells[0],
                courseCode = cells[1],
                courseName = cells[2],
                teacher = cells[3],
                category = cells[4],
                totalScore = cells[5],
                evaluated = cells[6] == "是",
                submitted = cells[7] == "是",
                teachingHours = cells[8],
                formPath = path,
            )
        }.toList()
    }

    fun parseEvaluationForm(html: String): EvaluationForm? {
        val form = Regex(
            """<form\b([^>]*)>(.*?)</form>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).firstOrNull { match ->
            attribute(match.groupValues[1], "action").contains("xspj_save.do")
        } ?: return null
        val formAttributes = form.groupValues[1]
        val formBody = form.groupValues[2]
        val hiddenFields = Regex(
            """<input\b([^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(formBody).mapNotNull { match ->
            val attributes = match.groupValues[1]
            if (!attribute(attributes, "type").equals("hidden", ignoreCase = true)) {
                return@mapNotNull null
            }
            val name = attribute(attributes, "name")
            if (name.isBlank()) null else EvaluationFormField(name, attribute(attributes, "value"))
        }.toList()

        val questions = rowRegex.findAll(formBody).mapNotNull { rowMatch ->
            val cells = cellRegex.findAll(rowMatch.groupValues[1]).map { it.groupValues[1] }.toList()
            if (cells.size < 2) return@mapNotNull null
            val questionId = Regex(
                """<input\b(?=[^>]*\bname\s*=\s*["']pj06xh["'])(?=[^>]*\bvalue\s*=\s*["']([^"']+)["'])[^>]*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(cells[0])?.groupValues?.get(1) ?: return@mapNotNull null
            val scores = hiddenFields
                .filter { it.name.startsWith("pj0601fz_${questionId}_") }
                .associate { it.name.removePrefix("pj0601fz_${questionId}_") to it.value }
            val options = Regex(
                """(<input\b[^>]*\btype\s*=\s*["']radio["'][^>]*>)(.*?)(?=<input\b|$)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).findAll(cells[1]).mapNotNull { optionMatch ->
                val input = optionMatch.groupValues[1]
                val attributes = input.substringAfter("<input").substringBeforeLast('>')
                val name = attribute(attributes, "name")
                if (name != "pj0601id_$questionId") return@mapNotNull null
                val id = attribute(attributes, "value")
                if (id.isBlank()) return@mapNotNull null
                EvaluationOption(
                    id = id,
                    label = plainText(optionMatch.groupValues[2]),
                    score = scores[id].orEmpty(),
                    selected = Regex("""\bchecked(?:\s*=\s*(?:["'][^"']*["']|[^\s>]+))?""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(attributes),
                )
            }.toList()
            if (options.isEmpty()) return@mapNotNull null
            EvaluationQuestion(
                id = decode(questionId),
                title = plainText(cells[0]),
                options = options,
            )
        }.toList()

        val textarea = Regex(
            """<textarea\b([^>]*)>(.*?)</textarea>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(formBody)
        val heading = Regex(
            """课程名称\s*[：:]\s*(.*?)\s*(?:&nbsp;|\s)+\s*评教大类\s*[：:]\s*(.*?)\s*(?:<|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)
        return EvaluationForm(
            courseName = heading?.groupValues?.getOrNull(1)?.let(::plainText).orEmpty(),
            category = heading?.groupValues?.getOrNull(2)?.let(::plainText).orEmpty(),
            actionPath = decode(attribute(formAttributes, "action")),
            hiddenFields = hiddenFields,
            questions = questions,
            suggestionField = textarea?.groupValues?.get(1)?.let { attribute(it, "name") }
                ?.takeIf(String::isNotBlank),
            suggestion = textarea?.groupValues?.get(2)?.let(::plainText).orEmpty(),
            readOnly = !Regex(
                """onclick\s*=\s*["'][^"']*saveData\s*\(""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(formBody),
        )
    }

    fun parseTimetable(html: String): AcademicTimetable? {
        val table = timetableTableRegex.find(html)?.groupValues?.get(1) ?: return null
        val rows = rowRegex.findAll(table).map { it.groupValues[1] }.toList()
        if (rows.size < 2) return null

        val periods = mutableListOf<TimetablePeriod>()
        val courses = mutableListOf<TimetableCourse>()
        rows.drop(1).forEachIndexed { rowOffset, rowHtml ->
            val cells = tableCellRegex.findAll(rowHtml).map { it.groupValues[1] }.toList()
            if (cells.size < 8) return@forEachIndexed
            val periodText = plainText(cells[0])
            val time = Regex("""(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})""").find(periodText)
            val periodIndex = rowOffset + 1
            periods += TimetablePeriod(
                index = periodIndex,
                label = periodText.substringBefore(time?.value.orEmpty()).trim().ifBlank { "第${periodIndex}大节" },
                startTime = time?.groupValues?.get(1).orEmpty(),
                endTime = time?.groupValues?.get(2).orEmpty(),
            )
            cells.drop(1).take(7).forEachIndexed { dayOffset, cell ->
                courses += parseTimetableCell(cell, dayOffset + 1, periodIndex)
            }
        }

        val terms = parseSelectOptions(html, "xnxq01id")
        val selectedTerm = terms.firstOrNull(AcademicTerm::selected)?.value
            ?: terms.firstOrNull()?.value.orEmpty()
        val note = rows.lastOrNull()
            ?.takeIf { tableCellRegex.findAll(it).count() < 8 }
            ?.let(::plainText)
            ?.removePrefix("备注:")
            ?.removePrefix("备注：")
            ?.trim()
            .orEmpty()
        val schemeId = Regex(
            """<input\b(?=[^>]*\bname\s*=\s*["']kbjcmsid["'])(?=[^>]*\bvalue\s*=\s*["']([^"']*)["'])[^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1).orEmpty()

        return AcademicTimetable(terms, selectedTerm, periods, courses, note, decode(schemeId))
    }

    fun parseTeachingWeek(html: String): TeachingWeek? {
        val updateScript = Regex(
            """li_showWeek.*?;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.value ?: return null
        val match = Regex(
            """第\s*(\d+)\s*周.*?/(\d+)\s*周""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(updateScript) ?: return null
        val week = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        if (week !in 1..total) return null
        return TeachingWeek(week, total)
    }

    fun isStudentLoginPage(html: String): Boolean =
        html.contains("LoginToXk", ignoreCase = true) ||
            (html.contains("userAccount", ignoreCase = true) &&
                html.contains("RANDOMCODE", ignoreCase = true))

    fun loginError(html: String): String? {
        val patterns = listOf(
            Regex("""alert\s*\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""id\s*=\s*["']error[^"']*["'][^>]*>(.*?)<""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.let(::plainText)?.takeIf(String::isNotBlank)
        }
    }

    private fun parseSelectOptions(html: String, name: String): List<AcademicTerm> {
        val select = Regex(
            """<select\b(?=[^>]*\bname\s*=\s*["']${Regex.escape(name)}["'])[^>]*>(.*?)</select>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1) ?: return emptyList()
        return optionRegex.findAll(select).mapNotNull { match ->
            val label = plainText(match.groupValues[2])
            if (label.isBlank()) return@mapNotNull null
            AcademicTerm(
                value = decode(valueRegex.find(match.groupValues[1])?.groupValues?.get(1).orEmpty()),
                label = label,
                selected = match.groupValues[1].contains("selected", ignoreCase = true),
            )
        }.toList()
    }

    private fun tableRows(html: String): Sequence<String> =
        rowRegex.findAll(html).map { it.groupValues[1] }

    private fun rowCells(row: String): List<String> =
        tableCellRegex.findAll(row).map { plainText(it.groupValues[1]) }.toList()

    private fun firstLink(source: String): String? = Regex(
        """<a\b(?=[^>]*\bhref\s*=\s*["']([^"']+)["'])[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(source)?.groupValues?.get(1)?.let(::decode)?.takeIf(String::isNotBlank)

    private fun attribute(attributes: String, name: String): String {
        val match = Regex(
            """(?:^|\s)${Regex.escape(name)}\s*=\s*(?:["']([^"']*)["']|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        ).find(attributes) ?: return ""
        return decode(match.groupValues[1].ifBlank { match.groupValues[2] })
    }

    private fun parseTimetableCell(cell: String, dayOfWeek: Int, periodIndex: Int): List<TimetableCourse> {
        val detailed = divRegex.findAll(cell).firstOrNull { match ->
            classRegex.find(match.groupValues[1])?.groupValues?.get(1)
                ?.split(Regex("\\s+"))
                ?.contains("kbcontent") == true && plainText(match.groupValues[2]).isNotBlank()
        }?.groupValues?.get(2) ?: return emptyList()

        return detailed.split(Regex("(?:<br\\s*/?>\\s*)?-{5,}(?:\\s*<br\\s*/?>)?", RegexOption.IGNORE_CASE))
            .mapNotNull { block ->
                val details = titledFontRegex.findAll(block).associate { match ->
                    plainText(match.groupValues[1]) to plainText(match.groupValues[2])
                }
                val teacher = details["老师"].orEmpty()
                val weeksWithSections = details["周次(节次)"].orEmpty()
                val location = details["教室"].orEmpty()
                val name = plainText(titledFontRegex.replace(block, " "))
                    .removeSuffix("P")
                    .trim()
                if (name.isBlank()) return@mapNotNull null
                val section = Regex("""\[(\d{1,2})-(\d{1,2})节]""").find(weeksWithSections)
                val startSection = section?.groupValues?.get(1)?.toIntOrNull() ?: periodIndex * 2 - 1
                val endSection = section?.groupValues?.get(2)?.toIntOrNull() ?: periodIndex * 2
                val weeks = weeksWithSections.replace(Regex("""\[\d{1,2}-\d{1,2}节]"""), "").trim()
                TimetableCourse(
                    id = "$dayOfWeek-$periodIndex-$name-$weeks-$location",
                    dayOfWeek = dayOfWeek,
                    periodIndex = periodIndex,
                    startSection = startSection,
                    endSection = endSection,
                    name = name,
                    teacher = teacher,
                    weeks = weeks,
                    weekNumbers = parseWeekNumbers(weeks),
                    location = location,
                )
            }
    }

    private fun parseWeekNumbers(source: String): Set<Int> {
        val rangeText = source.substringBefore("(周").substringBefore("（周")
        val oddOnly = source.contains("单")
        val evenOnly = source.contains("双")
        return Regex("""(\d+)(?:\s*-\s*(\d+))?""").findAll(rangeText).flatMap { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toIntOrNull() ?: start
            (start..end).asSequence()
        }.filter { (!oddOnly || it % 2 == 1) && (!evenOnly || it % 2 == 0) }.toSet()
    }

    private fun plainText(source: String): String = decode(
        tagRegex.replace(
            source
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("</p>", RegexOption.IGNORE_CASE), " "),
            " ",
        ),
    ).replace(Regex("\\s+"), " ").trim()

    private fun decode(source: String): String {
        val named = source
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
        return numericEntityRegex.replace(named) { match ->
            val raw = match.groupValues[1]
            val radix = if (raw.startsWith("x", ignoreCase = true)) 16 else 10
            raw.removePrefix("x").removePrefix("X").toIntOrNull(radix)
                ?.let { codePoint -> String(Character.toChars(codePoint)) }
                ?: match.value
        }
    }

    private const val GradeColumnCount = 20
    private const val SelectionResultColumnCount = 8
    private const val EvaluationBatchColumnCount = 7
    private const val EvaluationCourseColumnCount = 19
}

internal data class TeachingWeek(
    val week: Int,
    val totalWeeks: Int,
)
