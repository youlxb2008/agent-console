package com.youlxb2008.admin.modules.system.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.youlxb2008.admin.modules.system.entity.Notice;
import com.youlxb2008.admin.modules.system.entity.NoticeRead;
import com.youlxb2008.admin.modules.system.entity.NoticeRecipient;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.service.NoticeReadService;
import com.youlxb2008.admin.modules.system.service.NoticeRecipientService;
import com.youlxb2008.admin.modules.system.service.NoticeService;
import com.youlxb2008.admin.modules.system.vo.NoticeVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.CurrentUser;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.PageSearch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 消息通知 Controller
 * 提供通知的增删改查接口，需要 notice:view 权限查看，notice:create/update/delete 权限操作
 * 同时提供用户侧的未读计数、通知列表、已读标记等接口
 */
@RestController
@RequestMapping(path = "/system/notice")
@Slf4j
@Tag(name = "消息通知", description = "通知CRUD")
public class NoticeController {

	@Autowired
	private NoticeService noticeService;

	@Autowired
	private NoticeReadService noticeReadService;

	@Autowired
	private NoticeRecipientService noticeRecipientService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// ==================== 管理端接口 ====================

	/**
	 * 分页查询通知列表
	 * 
	 * @param notice 查询条件（按 title/type/status 模糊或精确匹配）
	 * @param page   分页参数
	 * @return 分页数据
	 */
	@Operation(summary = "分页查询通知列表")
	@RequiresPermissions("notice:view")
	@PostMapping("/list")
	public ResultVO list(Notice notice, PageSearch page) {
		LambdaQueryWrapper<Notice> queryWrapper = new LambdaQueryWrapper<>();
		// 标题模糊查询
		if (notice.getTitle() != null && !notice.getTitle().trim().isEmpty()) {
			queryWrapper.like(Notice::getTitle, notice.getTitle().trim());
		}
		// 类型精确查询
		if (notice.getType() != null) {
			queryWrapper.eq(Notice::getType, notice.getType());
		}
		// 状态精确查询
		if (notice.getStatus() != null) {
			queryWrapper.eq(Notice::getStatus, notice.getStatus());
		}
		queryWrapper.orderByDesc(Notice::getId);
		IPage<Notice> pageData = noticeService.page(page.toDBPage(), queryWrapper);
		// Entity → VO 转换，避免 Entity 出栈泄露内部字段
		IPage<NoticeVO> voPage = pageData.convert(this::convertToVO);
		return ResultVO.getSuccessVO("查询列表成功").setData(voPage);
	}

	/**
	 * 根据 ID 查询通知详情
	 * 
	 * @param id 通知 ID
	 * @return 通知详情
	 */
	@Operation(summary = "查询通知详情")
	@RequiresPermissions("notice:view")
	@GetMapping("/getById")
	public ResultVO getById(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		Notice notice = noticeService.getById(id);
		if (notice == null) {
			return ResultVO.getFailedVO("Notice not found");
		}
		// Entity → VO 转换后返回
		return ResultVO.getSuccessVO("查询详情成功").setData(convertToVO(notice));
	}

	/**
	 * 新增通知
	 * 
	 * @param currentUser 当前登录用户
	 * @param notice      通知实体（可携带 recipientUserIds 指定定向接收者）
	 * @return 操作结果
	 */
	@Operation(summary = "新增通知")
	@RequiresPermissions("notice:create")
	@PostMapping("/add")
	@Transactional
	public ResultVO add(@CurrentUser User currentUser, @RequestBody Notice notice) {
		// 自动填充发布人（从当前登录用户获取）
		notice.setPublisher(currentUser.getUsername());
		noticeService.save(notice);
		// 如果指定了接收者用户ID列表，批量插入 t_notice_recipient
		if (notice.getRecipientUserIds() != null && !notice.getRecipientUserIds().isEmpty()) {
			saveRecipients(notice.getId(), notice.getRecipientUserIds());
		}
		log.info("添加通知成功title={}", notice.getTitle());
		return ResultVO.getSuccessVO("保存成功");
	}

	/**
	 * 修改通知
	 * 
	 * @param notice 通知实体（必须包含 id，可携带 recipientUserIds 更新定向接收者）
	 * @return 操作结果
	 */
	@Operation(summary = "修改通知")
	@RequiresPermissions("notice:update")
	@PostMapping("/update")
	@Transactional
	public ResultVO update(@RequestBody Notice notice) {
		if (notice.getId() == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		Notice existing = noticeService.getById(notice.getId());
		if (existing == null) {
			return ResultVO.getFailedVO("Notice not found");
		}
		noticeService.updateById(notice);
		// 如果请求体中携带了 recipientUserIds，则更新接收者列表
		if (notice.getRecipientUserIds() != null) {
			// 先删除旧的接收者记录
			noticeRecipientService.remove(
					Wrappers.<NoticeRecipient>lambdaQuery().eq(NoticeRecipient::getNoticeId, notice.getId()));
			// 如果列表非空，批量插入新记录；空列表表示转为全员广播
			if (!notice.getRecipientUserIds().isEmpty()) {
				saveRecipients(notice.getId(), notice.getRecipientUserIds());
			}
		}
		log.info("更新通知成功id={}", notice.getId());
		return ResultVO.getSuccessVO("修改成功");
	}

	/**
	 * 根据 ID 删除通知
	 * 
	 * @param id 通知 ID
	 * @return 操作结果
	 */
	@Operation(summary = "删除通知")
	@RequiresPermissions("notice:delete")
	@GetMapping("/delete")
	public ResultVO delete(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		Notice notice = noticeService.getById(id);
		if (notice == null) {
			return ResultVO.getFailedVO("Notice not found");
		}
		noticeService.removeById(id);
		log.info("删除通知成功id={}", id);
		return ResultVO.getSuccessVO("删除成功");
	}

	// ==================== 用户侧接口 ====================

	/**
	 * 获取当前用户的未读通知总数
	 * <p>
	 * 查询逻辑：status=1 的通知中，当前用户未读（t_notice_read 中无记录）
	 * 且可见（无定向记录 OR 在定向列表中）的数量
	 * </p>
	 * 
	 * @param currentUser 当前登录用户
	 * @return 未读通知数量
	 */
	@Operation(summary = "获取当前用户未读通知数")
	@GetMapping("/myUnreadCount")
	public ResultVO myUnreadCount(@CurrentUser User currentUser) {
		Long userId = currentUser.getId();

		/*
		 * 分步查询：
		 * 1. 查出当前用户被定向到的通知ID列表（作为接收者）
		 * 2. 查出对当前用户可见且未读的通知数量：
		 * - status=1（已发布）
		 * - 无定向记录（全员广播）OR 当前用户在定向列表中
		 * - t_notice_read 中无记录（未读）
		 */
		// 步骤1：查出当前用户作为接收者的通知ID
		List<Long> targetedNoticeIds = noticeRecipientService.list(
				Wrappers.<NoticeRecipient>lambdaQuery()
						.eq(NoticeRecipient::getUserId, userId)
						.select(NoticeRecipient::getNoticeId))
				.stream().map(NoticeRecipient::getNoticeId).collect(Collectors.toList());

		// 步骤2：查出当前用户已读的通知ID
		List<Long> readNoticeIds = noticeReadService.list(
				Wrappers.<NoticeRead>lambdaQuery()
						.eq(NoticeRead::getUserId, userId)
						.select(NoticeRead::getNoticeId))
				.stream().map(NoticeRead::getNoticeId).collect(Collectors.toList());

		// 步骤3：构建可见通知查询条件
		LambdaQueryWrapper<Notice> visibleWrapper = Wrappers.<Notice>lambdaQuery()
				.eq(Notice::getStatus, 1);
		// 排除已读的通知
		if (!readNoticeIds.isEmpty()) {
			visibleWrapper.notIn(Notice::getId, readNoticeIds);
		}
		// 可见性过滤：无定向记录（全员广播）OR 在定向列表中
		if (!targetedNoticeIds.isEmpty()) {
			// 查出没有任何定向记录的通知ID（全员广播）
			List<Long> allNoticeIdsWithRecipient = noticeRecipientService.list(
					Wrappers.<NoticeRecipient>lambdaQuery()
							.select(NoticeRecipient::getNoticeId))
					.stream().map(NoticeRecipient::getNoticeId).distinct().collect(Collectors.toList());

			if (allNoticeIdsWithRecipient.isEmpty()) {
				// 所有通知都没有定向记录，即全部为全员广播，无需额外过滤
			} else {
				// 可见 = 全员广播的（不在定向列表中） OR 当前用户被定向到的
				visibleWrapper.and(w -> {
					// 全员广播的通知：没有对应的 recipient 记录
					w.notIn(Notice::getId, allNoticeIdsWithRecipient)
							// 或者当前用户在定向列表中
							.or().in(Notice::getId, targetedNoticeIds);
				});
			}
		} else {
			// 当前用户不在任何定向列表中，只能看全员广播的通知
			List<Long> allNoticeIdsWithRecipient = noticeRecipientService.list(
					Wrappers.<NoticeRecipient>lambdaQuery()
							.select(NoticeRecipient::getNoticeId))
					.stream().map(NoticeRecipient::getNoticeId).distinct().collect(Collectors.toList());

			if (!allNoticeIdsWithRecipient.isEmpty()) {
				// 排除有定向记录的通知（只保留全员广播的）
				visibleWrapper.notIn(Notice::getId, allNoticeIdsWithRecipient);
			}
		}

		long count = noticeService.count(visibleWrapper);
		return ResultVO.getSuccessVO("查询成功").setData(count);
	}

	/**
	 * 获取当前用户的通知列表（带 isRead 已读状态）
	 * <p>
	 * 查询逻辑：查 status=1 且（无定向 OR 当前用户在定向列表中）的通知，
	 * LEFT JOIN t_notice_read 判断是否已读，按 created_at DESC 排序。
	 * 由于需要 LEFT JOIN，使用 jdbcTemplate 实现分页查询。
	 * </p>
	 * 
	 * @param currentUser 当前登录用户
	 * @param page        分页参数
	 * @return 通知列表（分页，含 isRead 字段）
	 */
	@Operation(summary = "获取当前用户通知列表")
	@GetMapping("/myNotices")
	public ResultVO myNotices(@CurrentUser User currentUser, PageSearch page) {
		Long userId = currentUser.getId();
		int offset = (page.getCurPage() - 1) * page.getPageSize();
		int limit = page.getPageSize();

		/*
		 * 使用原生 SQL 实现分页查询：
		 * - 只查 status=1（已发布）的通知
		 * - 可见性：无定向记录 OR 当前用户在定向列表中
		 * - LEFT JOIN t_notice_read 判断是否已读
		 * - 按 created_at DESC 排序
		 */

		// 查询总数
		String countSql = "SELECT COUNT(1) FROM t_notice n " +
				"WHERE n.status = 1 " +
				"AND (NOT EXISTS (SELECT 1 FROM t_notice_recipient r WHERE r.notice_id = n.id) " +
				"OR EXISTS (SELECT 1 FROM t_notice_recipient r WHERE r.notice_id = n.id AND r.user_id = ?))";
		Long total = jdbcTemplate.queryForObject(countSql, Long.class, userId);

		// 查询分页数据
		String dataSql = "SELECT n.id, n.title, n.content, n.type, n.status, n.publisher, n.remark, n.created_at, " +
				"CASE WHEN nr.id IS NOT NULL THEN 1 ELSE 0 END AS is_read " +
				"FROM t_notice n " +
				"LEFT JOIN t_notice_read nr ON n.id = nr.notice_id AND nr.user_id = ? " +
				"WHERE n.status = 1 " +
				"AND (NOT EXISTS (SELECT 1 FROM t_notice_recipient r WHERE r.notice_id = n.id) " +
				"OR EXISTS (SELECT 1 FROM t_notice_recipient r WHERE r.notice_id = n.id AND r.user_id = ?)) " +
				"ORDER BY n.created_at DESC " +
				"LIMIT ? OFFSET ?";

		List<NoticeVO> voList = jdbcTemplate.query(dataSql,
				(rs, rowNum) -> {
					NoticeVO vo = new NoticeVO();
					vo.setId(rs.getLong("id"));
					vo.setTitle(rs.getString("title"));
					vo.setContent(rs.getString("content"));
					vo.setType(rs.getInt("type"));
					vo.setStatus(rs.getInt("status"));
					vo.setPublisher(rs.getString("publisher"));
					vo.setRemark(rs.getString("remark"));
					vo.setCreateTime(rs.getTimestamp("created_at"));
					// is_read: 1=已读，0=未读
					vo.setIsRead(rs.getInt("is_read") == 1);
					return vo;
				},
				userId, userId, limit, offset);

		// 构造分页结果
		return ResultVO.getSuccessVO("查询成功").put("records", voList).put("total", total)
				.put("curPage", page.getCurPage()).put("pageSize", page.getPageSize());
	}

	/**
	 * 标记单条通知为已读（幂等操作）
	 * <p>
	 * 如果 (noticeId, userId) 的已读记录不存在，则插入一条；
	 * 如果已存在则不做任何操作，保证幂等性。
	 * </p>
	 * 
	 * @param currentUser 当前登录用户
	 * @param id          通知 ID
	 * @return 操作结果
	 */
	@Operation(summary = "标记单条通知为已读")
	@PostMapping("/markRead/{id}")
	public ResultVO markRead(@CurrentUser User currentUser, @PathVariable("id") Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("通知ID不能为空");
		}
		// 校验通知是否存在
		Notice notice = noticeService.getById(id);
		if (notice == null) {
			return ResultVO.getFailedVO("通知不存在");
		}
		Long userId = currentUser.getId();
		// 幂等：检查是否已存在已读记录
		long exists = noticeReadService.count(
				Wrappers.<NoticeRead>lambdaQuery()
						.eq(NoticeRead::getNoticeId, id)
						.eq(NoticeRead::getUserId, userId));
		if (exists == 0) {
			NoticeRead readRecord = new NoticeRead();
			readRecord.setNoticeId(id);
			readRecord.setUserId(userId);
			readRecord.setReadAt(new Date());
			noticeReadService.save(readRecord);
			log.info("用户{}标记通知{}为已读", userId, id);
		}
		return ResultVO.getSuccessVO("标记成功");
	}

	/**
	 * 标记当前用户所有未读通知为已读
	 * <p>
	 * 查询所有对当前用户可见且未读的通知ID，批量插入 t_notice_read。
	 * </p>
	 * 
	 * @param currentUser 当前登录用户
	 * @return 操作结果
	 */
	@Operation(summary = "标记所有未读通知为已读")
	@PostMapping("/markAllRead")
	@Transactional
	public ResultVO markAllRead(@CurrentUser User currentUser) {
		Long userId = currentUser.getId();

		/*
		 * 查出对当前用户可见的未读通知ID：
		 * - status=1（已发布）
		 * - 可见（无定向 OR 在定向列表中）
		 * - t_notice_read 中无记录（未读）
		 */
		String sql = "SELECT n.id FROM t_notice n " +
				"LEFT JOIN t_notice_read nr ON n.id = nr.notice_id AND nr.user_id = ? " +
				"WHERE n.status = 1 " +
				"AND nr.id IS NULL " +
				"AND (NOT EXISTS (SELECT 1 FROM t_notice_recipient r WHERE r.notice_id = n.id) " +
				"OR EXISTS (SELECT 1 FROM t_notice_recipient r WHERE r.notice_id = n.id AND r.user_id = ?))";

		List<Long> unreadNoticeIds = jdbcTemplate.queryForList(sql, Long.class, userId, userId);

		if (unreadNoticeIds.isEmpty()) {
			return ResultVO.getSuccessVO("无未读通知");
		}

		// 批量插入已读记录
		Date now = new Date();
		List<NoticeRead> readRecords = new ArrayList<>();
		for (Long noticeId : unreadNoticeIds) {
			NoticeRead readRecord = new NoticeRead();
			readRecord.setNoticeId(noticeId);
			readRecord.setUserId(userId);
			readRecord.setReadAt(now);
			readRecords.add(readRecord);
		}
		noticeReadService.saveBatch(readRecords);
		log.info("用户{}批量标记{}条通知为已读", userId, readRecords.size());
		return ResultVO.getSuccessVO("标记成功").setData(readRecords.size());
	}

	/**
	 * 获取某条通知的接收者 userId 列表
	 * 
	 * @param id 通知 ID
	 * @return 接收者 userId 列表
	 */
	@Operation(summary = "获取通知接收者列表")
	@RequiresPermissions("notice:update")
	@GetMapping("/getRecipients/{id}")
	public ResultVO getRecipients(@PathVariable("id") Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("通知ID不能为空");
		}
		// 校验通知是否存在
		Notice notice = noticeService.getById(id);
		if (notice == null) {
			return ResultVO.getFailedVO("通知不存在");
		}
		// 查询该通知的所有接收者用户ID
		List<Long> userIds = noticeRecipientService.list(
				Wrappers.<NoticeRecipient>lambdaQuery()
						.eq(NoticeRecipient::getNoticeId, id)
						.select(NoticeRecipient::getUserId))
				.stream().map(NoticeRecipient::getUserId).collect(Collectors.toList());

		return ResultVO.getSuccessVO("查询成功").setData(userIds);
	}

	/**
	 * 设置某条通知的接收者
	 * <p>
	 * 先删除该通知的旧 recipient 记录，再批量插入新记录（事务内）。
	 * 如果 userIds 为空或 null，则删除所有 recipient 记录（转为全员广播）。
	 * </p>
	 * 
	 * @param id      通知 ID
	 * @param userIds 接收者用户ID列表
	 * @return 操作结果
	 */
	@Operation(summary = "设置通知接收者")
	@RequiresPermissions("notice:update")
	@PostMapping("/setRecipients/{id}")
	@Transactional
	public ResultVO setRecipients(@PathVariable("id") Long id, @RequestBody(required = false) List<Long> userIds) {
		if (id == null) {
			return ResultVO.getFailedVO("通知ID不能为空");
		}
		// 校验通知是否存在
		Notice notice = noticeService.getById(id);
		if (notice == null) {
			return ResultVO.getFailedVO("通知不存在");
		}

		// 先删除该通知的所有旧接收者记录
		noticeRecipientService.remove(
				Wrappers.<NoticeRecipient>lambdaQuery().eq(NoticeRecipient::getNoticeId, id));

		// 如果 userIds 非空，批量插入新记录；否则转为全员广播（不插入记录）
		if (userIds != null && !userIds.isEmpty()) {
			saveRecipients(id, userIds);
			log.info("通知{}设置{}个接收者", id, userIds.size());
		} else {
			log.info("通知{}转为全员广播", id);
		}
		return ResultVO.getSuccessVO("设置成功");
	}

	// ==================== 私有方法 ====================

	/**
	 * 通知实体转视图对象
	 * 
	 * @param notice 通知实体
	 * @return 通知 VO（出参）
	 */
	private NoticeVO convertToVO(Notice notice) {
		NoticeVO vo = new NoticeVO();
		vo.setId(notice.getId());
		vo.setTitle(notice.getTitle());
		vo.setContent(notice.getContent());
		vo.setType(notice.getType());
		vo.setStatus(notice.getStatus());
		vo.setPublisher(notice.getPublisher());
		vo.setRemark(notice.getRemark());
		vo.setCreateTime(notice.getCreatedAt());
		return vo;
	}

	/**
	 * 批量保存通知接收者记录
	 * 
	 * @param noticeId 通知ID
	 * @param userIds  接收者用户ID列表
	 */
	private void saveRecipients(Long noticeId, List<Long> userIds) {
		List<NoticeRecipient> recipients = new ArrayList<>();
		for (Long uid : userIds) {
			NoticeRecipient recipient = new NoticeRecipient();
			recipient.setNoticeId(noticeId);
			recipient.setUserId(uid);
			recipients.add(recipient);
		}
		noticeRecipientService.saveBatch(recipients);
	}
}
