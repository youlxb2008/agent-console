package com.youlxb2008.admin.modules.objective.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.exceptions.BadRequestException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * descMd 覆写段边界 bug 修复回归（2026-09-14）。
 * <p>
 * 原 {@code DESC_SECTION} lookahead {@code \n##\s} 匹配任意二级标题——descMd 本身含 {@code ##}
 * 标题时 lazy 匹配提前截断，旧内容被当作 rest 保留，每次 PATCH 净增内容（目标 md 膨胀、
 * 新旧段落并存，某长描述目标曾实锤 3438 字节重复结构）。
 * <p>
 * 修复后：边界只认模板保留段（{@code ## 子项} / {@code ## 反思}）；descMd 内其他 {@code ##}
 * 标题（含重复的 {@code ## 描述}）都算描述正文；保留段标题行明确 400（fail-fast）。
 * <p>
 * 使用独立内存 H2 与临时目标目录，不触碰开发库/真实数据。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcobjdesc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		"web.data-root=./target/test-data-objective-desc",
		"objectives.root=./target/test-objectives-desc" })
class ObjectiveDescMdPatchTest {

	@Resource
	private ObjectiveStorageManager manager;

	private static final AtomicLong SEQ = new AtomicLong(0);

	/** 建一个目标文件并返回 bodyPath（agentId 固定 999，目录随 objectives.root 隔离） */
	private String newBody(String descMd) {
		String oid = "o_" + String.format("%010d", SEQ.incrementAndGet());
		return manager.writeNewBody(999L, oid, descMd);
	}

	/** 统计独立成行的段标题出现次数 */
	private int countSection(String text, String title) {
		Matcher m = Pattern.compile("(?m)^" + Pattern.quote(title) + "\\s*$").matcher(text);
		int c = 0;
		while (m.find()) {
			c++;
		}
		return c;
	}

	private Path resolve(String bodyPath) {
		return Paths.get("./target/test-objectives-desc").resolve(bodyPath);
	}

	/** T1：descMd 含非保留 ## 标题 → 完整保留为描述正文，子项/反思段保留，描述段仅一处 */
	@Test
	void descMdWithH2KeptIntact() {
		String bodyPath = newBody("第一版描述");
		String desc = "概述\n\n## 背景\n\n背景内容\n\n## 目标\n\n目标内容";
		String out = manager.replaceDesc(bodyPath, desc);
		assertTrue(out.startsWith("## 描述\n概述"), "描述段以新内容开头");
		assertTrue(out.contains("## 背景"), "descMd 内 ## 标题应完整保留");
		assertTrue(out.contains("目标内容"));
		assertEquals(1, countSection(out, "## 描述"), "描述段标题仅一处");
		assertEquals(1, countSection(out, "## 子项"), "子项段保留");
		assertEquals(1, countSection(out, "## 反思"), "反思段保留");
	}

	/** T2：descMd 含独立成行的保留段标题（## 子项 / ## 反思）→ 400 拒绝；行内含字样但非标题行 → 放行 */
	@Test
	void descMdWithReservedSectionRejected() {
		String bodyPath = newBody("初始");
		assertThrows(BadRequestException.class,
				() -> manager.replaceDesc(bodyPath, "内容\n## 子项\n- [ ] x"), "## 子项 行应 400");
		assertThrows(BadRequestException.class,
				() -> manager.replaceDesc(bodyPath, "内容\n## 反思\n- 记录"), "## 反思 行应 400");
		assertThrows(BadRequestException.class,
				() -> manager.replaceDesc(bodyPath, "内容\n##子项"), "无空格标题行同样 400");
		String out = manager.replaceDesc(bodyPath, "## 子项说明不是保留段\n正文");
		assertTrue(out.contains("## 子项说明不是保留段"), "非独立保留段标题行放行");
	}

	/** T3：连续多次 PATCH 同一 descMd（含 ## 标题）→ 文件字节数稳定，不膨胀 */
	@Test
	void repeatedPatchStableSize() {
		String bodyPath = newBody("v1");
		String desc = "描述正文\n\n## 小节A\n\nA 内容\n\n## 小节B\n\nB 内容";
		String out = manager.replaceDesc(bodyPath, desc);
		int size1 = out.length();
		for (int i = 0; i < 3; i++) {
			out = manager.replaceDesc(bodyPath, desc);
		}
		assertEquals(size1, out.length(), "连续 PATCH 同一 descMd 文件长度应稳定（旧 bug 每次净增）");
		assertEquals(1, countSection(out, "## 小节A"), "小节标题不重复");
	}

	/** T4：历史 bug 造成的损坏文件（重复段）→ PATCH 后描述段正确替换、不再膨胀（rest 重复段由数据修复清理） */
	@Test
	void corruptedFileDescReplacedAndStable() throws Exception {
		String bodyPath = newBody("初始");
		String corrupted = "## 描述\n## 描述\n- 旧内容A\n\n## 验收标准\n- 旧标准\n\n"
				+ "## 子项\n- [x] 旧子项\n\n## 反思\n- 旧反思\n\n"
				+ "## 描述\n- 重复描述\n\n## 子项\n- [x] 旧子项\n\n## 反思\n";
		Files.writeString(resolve(bodyPath), corrupted, StandardCharsets.UTF_8);
		String out = manager.replaceDesc(bodyPath, "修复后的描述");
		assertTrue(out.startsWith("## 描述\n修复后的描述"), "描述段被正确替换");
		assertFalse(out.contains("旧内容A"), "边界内旧描述正文（含重复 ## 描述）被清除");
		assertTrue(out.contains("- [x] 旧子项"), "第一份子项段保留");
		int size1 = out.length();
		String out2 = manager.replaceDesc(bodyPath, "修复后的描述");
		assertEquals(size1, out2.length(), "损坏文件修复后连续 PATCH 长度稳定（不再膨胀）");
	}

	/** T5：正常 PATCH 回归——无 ## 的 descMd、空 descMd、子项 checkbox 保留 */
	@Test
	void normalPatchRegression() throws Exception {
		String bodyPath = newBody("初始描述");
		String out = manager.replaceDesc(bodyPath, "新描述");
		assertTrue(out.startsWith("## 描述\n新描述\n"));
		assertTrue(out.contains("\n## 子项\n\n## 反思\n"), "空模板子项/反思结构保留");
		String out2 = manager.replaceDesc(bodyPath, "");
		assertTrue(out2.startsWith("## 描述\n\n## 子项"), "空 descMd 合法（清空描述段）");
		// 带子项内容的文件：PATCH 描述不影响 checkbox
		String bodyPath2 = newBody("目标X");
		String withItems = manager.replaceDesc(bodyPath2, "描述X");
		String manual = withItems.replace("## 子项\n", "## 子项\n- [x] 已完成项\n- [ ] 待办项\n");
		Files.writeString(resolve(bodyPath2), manual, StandardCharsets.UTF_8);
		String out3 = manager.replaceDesc(bodyPath2, "描述X2");
		assertTrue(out3.contains("- [x] 已完成项"), "子项 checkbox 不受描述覆写影响");
		assertTrue(out3.contains("- [ ] 待办项"));
	}
}
