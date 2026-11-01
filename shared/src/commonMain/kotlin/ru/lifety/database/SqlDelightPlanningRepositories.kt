package ru.lifety.database

import app.cash.sqldelight.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import ru.lifety.core.model.Goal
import ru.lifety.core.model.Task
import ru.lifety.core.model.TaskPlan
import ru.lifety.core.model.PlanReport
import ru.lifety.core.model.HealthSnapshot
import ru.lifety.core.model.Expense
import ru.lifety.core.model.Person
import ru.lifety.core.repository.*
import ru.lifety.core.planning.*
import ru.lifety.database.generated.LifetyDatabase
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import ru.lifety.core.planner.taskIntervalError

class SqlDelightGoalRepository(database: LifetyDatabase, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : GoalRepository {
    private val queries = database.lifetyQueries
    override fun observeAll(): Flow<List<Goal>> = queries.selectGoals(::Goal).asFlow().mapToList(dispatcher)
    override suspend fun upsert(goal: Goal) = withContext(dispatcher) {
        require(goal.id.isNotBlank() && goal.title.isNotBlank() && goal.title.length <= 200 && goal.description.length <= 6000)
        queries.upsertGoal(goal.id, goal.title.trim(), goal.description.trim(), goal.targetAt, goal.isCompleted)
        Unit
    }
    override suspend fun delete(id: String) = withContext(dispatcher) {
        queries.transaction { queries.clearGoalLinks(id); queries.deleteGoal(id) }
    }
}

class SqlDelightPlanningRepository(database: LifetyDatabase, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : PlanningRepository {
    private val queries = database.lifetyQueries
    override fun observeAll(): Flow<List<TaskPlan>> = queries.selectTaskPlans(::TaskPlan).asFlow().mapToList(dispatcher)
    override fun observeReport(): Flow<PlanReport?> = queries.selectPlanReport(::PlanReport).asFlow().mapToOneOrNull(dispatcher)
    private fun readSnapshot() = PlanSnapshot(queries.selectTasks(::Task).executeAsList(), queries.selectGoals(::Goal).executeAsList(), queries.selectTaskPlans(::TaskPlan).executeAsList(), queries.selectScheduleBlocks(::scheduleBlock).executeAsList())
    override suspend fun snapshot(): PlanSnapshot = withContext(dispatcher) { queries.transactionWithResult { readSnapshot() } }
    override suspend fun saveTask(task: Task, details: TaskPlan?) = withContext(dispatcher) {
        require(task.id.isNotBlank() && task.title.isNotBlank())
        require(details == null || details.taskId == task.id && details.durationMinutes in 1..1440 && details.approach.length <= 6000)
        require(taskIntervalError(task, details?.durationMinutes) == null)
        queries.transaction {
            require(details?.goalId == null || queries.selectGoals(::Goal).executeAsList().any { it.id == details.goalId })
            queries.upsertTask(task.id, task.title.trim(), task.scheduledAt, task.deadlineAt, task.isCompleted)
            if (details == null) queries.deleteTaskPlan(task.id) else queries.upsertTaskPlan(task.id, details.durationMinutes, details.approach.trim(), details.goalId)
        }
    }
    override suspend fun apply(plan: ImportedPlan, now: Long) = withContext(dispatcher) {
        queries.transaction {
            val snapshot = readSnapshot()
            validateImportedPlan(plan, snapshot, now)
            val workspace = queries.readChatGptWorkspace()
            val sleep = if (plan.format == "lifety-plan-v2" && workspace.preferences.includeSleep)
                summarizeSleep(queries.selectHealthHistory(::HealthSnapshot).executeAsList(), now, TimeZone.currentSystemDefault()) else null
            val extra = selectedExtraContext(workspace.preferences, if (workspace.preferences.healthMetricIds.isNotEmpty()) queries.metricSnapshot() else ru.lifety.core.model.HealthMetricSnapshot(),
                if (workspace.preferences.includeSteps) queries.selectHealthHistory(::HealthSnapshot).executeAsList() else emptyList(),
                if (workspace.preferences.includeFinance) queries.selectExpenses(::Expense).executeAsList() else emptyList(),
                if (workspace.preferences.includePeople) queries.selectPeople(::personFromRow).executeAsList() else emptyList(), now, TimeZone.currentSystemDefault())
            validateChatGptResponse(plan, workspace, snapshot, sleep, now, TimeZone.currentSystemDefault(), extra)
            val tasks = snapshot.tasks.associateBy { it.id }
            plan.tasks.forEach { item ->
                val task = tasks.getValue(item.taskId)
                queries.upsertTask(task.id, task.title, Instant.parse(item.scheduledAt).toEpochMilliseconds(), task.deadlineAt, task.isCompleted)
                queries.upsertTaskPlan(task.id, item.durationMinutes, item.approach.trim(), item.goalId)
            }
            plan.deferredTasks.forEach { item ->
                val task = tasks.getValue(item.taskId)
                queries.upsertTask(task.id, task.title, null, task.deadlineAt, task.isCompleted)
            }
            queries.upsertPlanReport(plan.summary.trim(), now)
            val answeredDraft = workspace.draft?.takeIf { plan.format == "lifety-plan-v2" && it.id == plan.requestId }
            val sent = answeredDraft?.let { ChatGptSent(it.id, now, it.baseSnapshot, it.sourceFingerprint, it.profileFingerprint, it.baseline, awaitingReply = false) }
                ?: workspace.lastSent?.copy(awaitingReply = false)
            queries.writeChatGptWorkspace(workspace.copy(draft = null, lastSent = sent, receipt = ChatGptReceipt(now, plan.deferredTasks)))
        }
    }
}
