package com.youlxb2008.admin.modules.todo.service;

import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.todo.dto.TodoCreateReq;
import com.youlxb2008.admin.modules.todo.dto.TodoPatchReq;
import com.youlxb2008.admin.modules.todo.entity.Todo;
import com.youlxb2008.admin.modules.todo.mapper.TodoMapper;
import com.youlxb2008.admin.modules.todo.vo.TodoStatusCountVO;
import com.youlxb2008.admin.modules.todo.vo.TodoVO;

import lombok.extern.slf4j.Slf4j;

/**
 * 待办 Service（M5.2，agent 私域 todolist，调研池迁入）
 * <p>
 * 纯索引 CRUD（真相源即 t_todo 行，content 直接入库，无文件系统）。
 * 核心语义（任务书 M5.2-agent-todo.md）：
 * <ul>
 *   <li>todoId：d_ + 10 位 base62（照搬 nid/o_/l_ 实现），随机生成终身不变</li>
 *   <li>归属：agent_id 单值私域；越权（操作非本人 todo）→ 403；不存在/已软删 → 404</li>
 *   <li>排序：(due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC
 *       （M5.3：有截止日期在前按日期升序，无日期按优先级排后）</li>
 *   <li>软删：is_deleted=1（@TableLogic）；全链路查询 is_deleted=0（fail-closed）</li>
 *   <li>tags：逗号分隔字符串（split → trim → 去空 → 去重 → 重 join）</li>
 *   <li>状态机：0待办 → 1进行中 → 2已完成 / 3已放弃；PATCH 任意流转不强校验</li>
 * </ul>
 */
@Slf4j
@Service
public class TodoService extends ServiceImpl<TodoMapper, Todo> {

	/** 对外引用键前缀 d_ + 10 位 base62 */
	private static final String TODO_ID_PREFIX = "d_";
	private static final int TODO_ID_RANDOM_LEN = 10;
	private static final int TODO_ID_RETRY = 3;
	private static final Pattern TODO_ID_PATTERN = Pattern.compile("^d_[0-9A-Za-z]{10}$");
	private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** title ≤200 字符 */
	private static final int MAX_TITLE_LEN = 200;
	/** 状态合法范围（契约：0待办 1进行中 2已完成 3已放弃） */
	private static final int STATUS_MIN = 0;
	private static final int STATUS_MAX = 3;
	/** 优先级合法范围（契约：1-5，默认 3） */
	private static final int PRIORITY_MIN = 1;
	private static final int PRIORITY_MAX = 5;
	private static final int PRIORITY_DEFAULT = 3;

	@Autowired
	private AgentService agentService;

	// ============================================================
	// 定位 / 归属校验
	// ============================================================

	/**
	 * 按 todoId 查待办行（对外引用键，唯一；@TableLogic 自动过滤 is_deleted=1）
	 *
	 * @param todoId 对外引用键（d_ + 10 位 base62）
	 * @return 待办行；不存在/格式非法返回 null
	 */
	public Todo findByTodoId(String todoId) {
		if (!isTodoIdValid(todoId)) {
			return null;
		}
		return getOne(Wrappers.<Todo>lambdaQuery().eq(Todo::getTodoId, todoId), true);
	}

	/**
	 * 私域归属校验：待办须存在且归属当前 agent。
	 * <p>
	 * 任务书契约：越权（操作非本人 todo）→ <b>403</b>；不存在/已软删 → 404
	 * （与 objective 统一 404 防枚举不同——todo 契约明确区分，照契约实现）。
	 *
	 * @param agentId 当前 agent id
	 * @param todoId  待办 todoId
	 * @return 待办行（已确认归属）
	 */
	public Todo requireOwned(Long agentId, String todoId) {
		Todo todo = findByTodoId(todoId);
		if (todo == null) {
			throw new NotFoundException("待办不存在");
		}
		if (!agentId.equals(todo.getAgentId())) {
			throw new ForbiddenException("无权操作他人的待办");
		}
		return todo;
	}

	// ============================================================
	// 写操作
	// ============================================================

	/**
	 * 新建待办（契约 POST /api/agent/todos）：生成 todoId → 校验字段 → 落行。
	 * title 必填；content 可选；status 默认 0；priority 默认 3；tags 逗号串规范化。
	 *
	 * @param agentId 归属 agent id
	 * @param req     创建请求（title 必填）
	 * @return 新建的待办 VO（含 todoId）
	 */
	@Transactional(rollbackFor = Exception.class)
	public TodoVO create(Long agentId, TodoCreateReq req) {
		String title = prepareTitle(req.getTitle());
		Integer status = req.getStatus() == null ? 0 : req.getStatus();
		Integer priority = req.getPriority() == null ? PRIORITY_DEFAULT : req.getPriority();
		validateStatus(status);
		validatePriority(priority);
		String todoId = generateUniqueTodoId();
		Date now = new Date();
		Todo todo = new Todo();
		todo.setTodoId(todoId);
		todo.setAgentId(agentId);
		todo.setTitle(title);
		todo.setContent(req.getContent());
		todo.setStatus(status);
		todo.setPriority(priority);
		todo.setTags(normalizeTags(req.getTags()));
		todo.setDueDate(req.getDueDate());
		todo.setCreateTime(now);
		todo.setUpdateTime(now);
		save(todo);
		return toVO(todo);
	}

	/**
	 * 部分更新（契约 PATCH /api/agent/todos/{todoId}）：requireOwned（非本人 403/不存在 404）
	 * → 至少一项校验（全空 400）→ 更新字段 → 刷新 update_time。
	 * status 任意流转不做强校验（todolist 场景灵活优先）。
	 *
	 * @param agentId 当前 agent id
	 * @param todoId  待办 todoId
	 * @param req     PATCH 请求（全空 → 400）
	 * @return 更新后待办 VO
	 */
	@Transactional(rollbackFor = Exception.class)
	public TodoVO patchMeta(Long agentId, String todoId, TodoPatchReq req) {
		Todo todo = requireOwned(agentId, todoId);
		if (req == null || (req.getTitle() == null && req.getContent() == null && req.getStatus() == null
				&& req.getPriority() == null && req.getTags() == null && req.getDueDate() == null)) {
			throw new BadRequestException("至少修改一项（title/content/status/priority/tags/dueDate）");
		}
		Todo update = new Todo();
		update.setId(todo.getId());
		if (req.getTitle() != null) {
			update.setTitle(prepareTitle(req.getTitle()));
		}
		if (req.getContent() != null) {
			update.setContent(req.getContent());
		}
		if (req.getStatus() != null) {
			validateStatus(req.getStatus());
			update.setStatus(req.getStatus());
		}
		if (req.getPriority() != null) {
			validatePriority(req.getPriority());
			update.setPriority(req.getPriority());
		}
		if (req.getTags() != null) {
			update.setTags(normalizeTags(req.getTags()));
		}
		if (req.getDueDate() != null) {
			update.setDueDate(req.getDueDate());
		}
		update.setUpdateTime(new Date());
		updateById(update);

		// 回填内存实体供返回（避免再查库）
		if (update.getTitle() != null) {
			todo.setTitle(update.getTitle());
		}
		if (update.getContent() != null) {
			todo.setContent(update.getContent());
		}
		if (update.getStatus() != null) {
			todo.setStatus(update.getStatus());
		}
		if (update.getPriority() != null) {
			todo.setPriority(update.getPriority());
		}
		if (update.getTags() != null) {
			todo.setTags(update.getTags());
		}
		if (update.getDueDate() != null) {
			todo.setDueDate(update.getDueDate());
		}
		todo.setUpdateTime(update.getUpdateTime());
		return toVO(todo);
	}

	/**
	 * 软删（契约 DELETE /api/agent/todos/{todoId}）：requireOwned（非本人 403/不存在 404）
	 * → is_deleted=1。幂等：已软删/不存在统一 404（防枚举）。
	 *
	 * @param agentId 当前 agent id
	 * @param todoId  待办 todoId
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long agentId, String todoId) {
		requireOwned(agentId, todoId);
		boolean ok = lambdaUpdate().eq(Todo::getTodoId, todoId)
				.set(Todo::getIsDeleted, 1)
				.set(Todo::getUpdateTime, new Date())
				.update();
		if (!ok) {
			throw new NotFoundException("待办不存在");
		}
	}

	// ============================================================
	// 查询族
	// ============================================================

	/**
	 * agent 域：自己（当前 agent）的待办分页（契约 GET /api/agent/todos）。
	 * <p>
	 * 排序（M5.3）：{@code (due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC}
	 * ——有截止日期的按日期升序排最前（过期的自然在最前），无日期的按 priority DESC, id ASC 排在其后；
	 * status/priority 可选筛选；默认 pageSize=20（调用方钳制 ≤50）。
	 *
	 * @param agentId  当前 agent id
	 * @param status   状态筛选（可选）
	 * @param priority 优先级筛选（可选）
	 * @param page     页码（1 起）
	 * @param pageSize 每页条数
	 * @return 分页结果（IPage，Controller setData 自动转 BPage）
	 */
	public IPage<TodoVO> pageOwn(Long agentId, Integer status, Integer priority, long page, long pageSize) {
		LambdaQueryWrapper<Todo> wrapper = Wrappers.lambdaQuery();
		wrapper.eq(Todo::getAgentId, agentId)
				.eq(status != null, Todo::getStatus, status)
				.eq(priority != null, Todo::getPriority, priority);
		applyDueDateOrder(wrapper);
		IPage<Todo> result = page(new Page<>(page, pageSize), wrapper);
		return result.convert(this::toVO);
	}

	/**
	 * 详情（契约 GET /api/agent/todos/{todoId}）：须本人（非本人 403/不存在 404）。
	 *
	 * @param agentId 当前 agent id
	 * @param todoId  待办 todoId
	 * @return 详情 VO（含 content）
	 */
	public TodoVO getDetailOwned(Long agentId, String todoId) {
		Todo todo = requireOwned(agentId, todoId);
		return toVO(todo);
	}

	/**
	 * 门户：某 agent 的待办分页（契约 GET /api/portal/agents/{agentId}/todos，匿名只读）。
	 * <p>
	 * agent 不存在 → 404；status 可选筛选；排序同 agent 域
	 * （M5.3：有截止日期在前按日期升序，无日期按 priority DESC, id ASC）。
	 *
	 * @param agentId  agent id
	 * @param status   状态筛选（可选）
	 * @param page     页码（1 起）
	 * @param pageSize 每页条数
	 * @return 分页结果（IPage，Controller setData 自动转 BPage）
	 */
	public IPage<TodoVO> portalPageByAgent(Long agentId, Integer status, long page, long pageSize) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		LambdaQueryWrapper<Todo> wrapper = Wrappers.lambdaQuery();
		wrapper.eq(Todo::getAgentId, agentId)
				.eq(status != null, Todo::getStatus, status);
		applyDueDateOrder(wrapper);
		IPage<Todo> result = page(new Page<>(page, pageSize), wrapper);
		return result.convert(this::toVO);
	}

	/**
	 * 门户：某 agent 待办状态计数（全量口径，不受 status 筛选影响；同目标域
	 * portalStatusCountsByAgent，供 Tab 角标用）。仅统计未软删；agent 不存在 → 404。
	 *
	 * @param agentId agent id
	 * @return 各状态计数（total = 四态之和）
	 */
	public TodoStatusCountVO portalStatusCountsByAgent(Long agentId) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		QueryWrapper<Todo> wrapper = new QueryWrapper<>();
		wrapper.select("status", "COUNT(*) AS cnt")
				.eq("agent_id", agentId)
				.groupBy("status");
		Map<Integer, Long> byStatus = new HashMap<>();
		for (Map<String, Object> row : listMaps(wrapper)) {
			Object status = row.get("status");
			Object cnt = row.get("cnt");
			if (status instanceof Number s && cnt instanceof Number c) {
				byStatus.put(s.intValue(), c.longValue());
			}
		}
		TodoStatusCountVO vo = new TodoStatusCountVO();
		vo.setPending(byStatus.getOrDefault(0, 0L));
		vo.setDoing(byStatus.getOrDefault(1, 0L));
		vo.setDone(byStatus.getOrDefault(2, 0L));
		vo.setDropped(byStatus.getOrDefault(3, 0L));
		vo.setTotal(vo.getPending() + vo.getDoing() + vo.getDone() + vo.getDropped());
		return vo;
	}

	/**
	 * 统一排序（M5.3，agent 域与门户域共用）：
	 * {@code (due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC}。
	 * <p>
	 * MySQL 5.7 无 {@code NULLS LAST}，用 {@code (due_date IS NULL)} 布尔表达式升序实现
	 * （0=有日期在前，1=NULL 在后）；LambdaQueryWrapper 不支持表达式列，
	 * 经 {@code .last()} 拼接原始 ORDER BY（常量片段，无外部输入，无注入面）。
	 *
	 * @param wrapper 已拼好 WHERE 条件的查询包装器
	 */
	private void applyDueDateOrder(LambdaQueryWrapper<Todo> wrapper) {
		wrapper.last("ORDER BY (due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC");
	}

	// ============================================================
	// todoId 生成 / 校验
	// ============================================================

	/** 生成未被占用的 todoId（查索引去重；理论碰撞概率 62^10 分之一，仍兜底循环） */
	public String generateUniqueTodoId() {
		for (int i = 0; i < TODO_ID_RETRY; i++) {
			String todoId = generateTodoId();
			if (findByTodoId(todoId) == null) {
				return todoId;
			}
			log.warn("todoId 已被占用，重新生成 {}/{}", i + 1, TODO_ID_RETRY);
		}
		throw new BusinessException("todoId 生成冲突，请重试");
	}

	private String generateTodoId() {
		StringBuilder sb = new StringBuilder(TODO_ID_PREFIX);
		for (int i = 0; i < TODO_ID_RANDOM_LEN; i++) {
			sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
		}
		return sb.toString();
	}

	/** todoId 格式强校验（^d_[0-9A-Za-z]{10}$，防注入/防枚举）；不合法返回 false（调用方按 404 处理） */
	public boolean isTodoIdValid(String todoId) {
		return todoId != null && TODO_ID_PATTERN.matcher(todoId).matches();
	}

	// ============================================================
	// 内部：字段校验 / tags 规范化 / VO 转换
	// ============================================================

	/** 校验并返回规范标题（非空、≤200、非空白） */
	private String prepareTitle(String rawTitle) {
		if (rawTitle == null) {
			throw new BadRequestException("标题不能为空");
		}
		String title = rawTitle.trim();
		if (title.isEmpty()) {
			throw new BadRequestException("标题不能为空");
		}
		if (title.length() > MAX_TITLE_LEN) {
			throw new BadRequestException("标题长度不能超过 " + MAX_TITLE_LEN);
		}
		return title;
	}

	/**
	 * tags 逗号分隔字符串规范化：split → trim → 去空 → 去重（保留首次出现顺序）→ 重 join。
	 * <p>
	 * 对齐笔记 tags 惯例（同一 tag 只计 1 次）；null/空串 → null（不落空串）。
	 * 契约：tags 传逗号分隔字符串，不收 JSON 数组。
	 */
	public String normalizeTags(String rawTags) {
		if (rawTags == null || rawTags.isBlank()) {
			return null;
		}
		Set<String> set = new LinkedHashSet<>();
		for (String raw : rawTags.split(",")) {
			String tag = raw.trim();
			if (!tag.isEmpty()) {
				set.add(tag);
			}
		}
		return set.isEmpty() ? null : String.join(",", set);
	}

	private void validateStatus(Integer status) {
		if (status == null || status < STATUS_MIN || status > STATUS_MAX) {
			throw new BadRequestException("status 取值 0-3（0待办 1进行中 2已完成 3已放弃）");
		}
	}

	private void validatePriority(Integer priority) {
		if (priority == null || priority < PRIORITY_MIN || priority > PRIORITY_MAX) {
			throw new BadRequestException("priority 取值 1-5");
		}
	}

	/** 待办行 → VO（列表/详情共用，含 content） */
	private TodoVO toVO(Todo todo) {
		TodoVO vo = new TodoVO();
		vo.setTodoId(todo.getTodoId());
		vo.setTitle(todo.getTitle());
		vo.setContent(todo.getContent());
		vo.setStatus(todo.getStatus());
		vo.setPriority(todo.getPriority());
		vo.setTags(todo.getTags());
		vo.setDueDate(todo.getDueDate());
		vo.setCreateTime(todo.getCreateTime());
		vo.setUpdateTime(todo.getUpdateTime());
		return vo;
	}
}
