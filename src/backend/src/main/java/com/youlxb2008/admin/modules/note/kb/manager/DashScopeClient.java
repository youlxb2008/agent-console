package com.youlxb2008.admin.modules.note.kb.manager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.system.entity.SiteOption;
import com.youlxb2008.admin.modules.system.service.SiteOptionService;

import lombok.extern.slf4j.Slf4j;

/**
 * 阿里云百炼（DashScope）OpenAI 兼容接口客户端（知识库专用）
 * <p>
 * 只封装知识库需要的两个能力，零 AI 框架依赖（httpclient5 + fastjson2 均为项目既有依赖）：
 * <ul>
 *   <li>{@link #embed}：文本批量向量化 —— POST {base}/embeddings（模型默认 text-embedding-v3，1024 维）</li>
 *   <li>{@link #chat}：单轮问答 —— POST {base}/chat/completions（模型默认 qwen-plus，RAG 生成答案用）</li>
 * </ul>
 * API Key 从 t_option 表读取（optionKey = {@link #OPTION_API_KEY}），管理端「系统配置」页维护：
 * 不落配置文件、改 key 无需重启；未配置时 {@link #apiKeyOrNull()} 返回 null，
 * 调用方据此降级（检索退化为 BM25、问答直接提示配置）。
 */
@Slf4j
@Component
public class DashScopeClient {

	/** t_option 中存百炼 API Key 的键名（管理端系统配置页可维护） */
	public static final String OPTION_API_KEY = "dashscope.api_key";

	/** text-embedding-v3 单次请求上限 10 条文本（官方限制），批量向量化按此分批 */
	private static final int EMBED_BATCH_SIZE = 10;

	/** 兼容模式基地址（OpenAI 协议），可用内网代理/网关覆盖 */
	@Value("${kb.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
	private String baseUrl;

	/** 向量模型（1024 维；换模型须重建索引，embedding 维度与表注释一致） */
	@Value("${kb.dashscope.embedding-model:text-embedding-v3}")
	private String embeddingModel;

	/** 对话模型（RAG 问答生成） */
	@Value("${kb.dashscope.chat-model:qwen-plus}")
	private String chatModel;

	@Autowired
	private SiteOptionService optionService;

	/** 懒加载单例 HTTP 客户端（每次调用开销小，索引/问答频率低） */
	private volatile CloseableHttpClient httpClient;

	/**
	 * 读取百炼 API Key（t_option）
	 *
	 * @return key 明文；未配置返回 null（调用方自行降级，不在此抛错——
	 *         BM25 检索/建文本块在无 key 时仍应可用）
	 */
	public String apiKeyOrNull() {
		SiteOption opt = optionService.getOne(
				new QueryWrapper<SiteOption>().eq("option_key", OPTION_API_KEY).last("LIMIT 1"));
		String key = opt == null ? null : opt.getOptionValue();
		return (key == null || key.isBlank()) ? null : key.trim();
	}

	/**
	 * 文本批量向量化（内部自动按 10 条/批切分，返回顺序与入参一致）
	 *
	 * @param texts 文本列表（单条非空）
	 * @return 与入参等长的向量列表（每条 float[1024]）
	 * @throws BusinessException 网络/鉴权/限流等失败（含服务端错误信息）
	 */
	public List<float[]> embed(List<String> texts) {
		String apiKey = requireApiKey();
		List<float[]> result = new ArrayList<>(texts.size());
		// 按 DashScope 单批上限切块请求，逐批拼接（失败整批抛错，调用方决定重试/跳过）
		for (int from = 0; from < texts.size(); from += EMBED_BATCH_SIZE) {
			List<String> batch = texts.subList(from, Math.min(from + EMBED_BATCH_SIZE, texts.size()));
			result.addAll(embedBatch(apiKey, batch));
		}
		return result;
	}

	/**
	 * 单轮对话（RAG 生成答案）
	 *
	 * @param systemPrompt 系统提示词（角色/回答约束）
	 * @param userPrompt   用户提示词（问题 + 检索到的知识片段）
	 * @return 模型回答全文
	 */
	public String chat(String systemPrompt, String userPrompt) {
		String apiKey = requireApiKey();
		JSONObject body = new JSONObject();
		body.put("model", chatModel);
		body.put("temperature", 0.3); // 知识问答场景：偏低温度，答案更贴检索内容
		JSONArray messages = new JSONArray();
		messages.add(msg("system", systemPrompt));
		messages.add(msg("user", userPrompt));
		body.put("messages", messages);

		JSONObject resp = postJson(apiKey, baseUrl + "/chat/completions", body);
		// 兼容响应：choices[0].message.content
		try {
			return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
		} catch (Exception e) {
			throw new BusinessException("百炼问答响应解析失败：" + resp.toJSONString().substring(0,
					Math.min(300, resp.toJSONString().length())));
		}
	}

	/** 向量模型名（落 t_note_chunk.embed_model 用，与实际调用保持同源） */
	public String embeddingModelName() {
		return embeddingModel;
	}

	/* ==================== 内部实现 ==================== */

	/** 单批向量化请求（≤10 条） */
	private List<float[]> embedBatch(String apiKey, List<String> batch) {
		JSONObject body = new JSONObject();
		body.put("model", embeddingModel);
		body.put("input", batch);
		JSONObject resp = postJson(apiKey, baseUrl + "/embeddings", body);
		// 兼容响应：data[] 按 index 回填，保证与入参顺序一致
		try {
			JSONArray data = resp.getJSONArray("data");
			float[][] ordered = new float[batch.size()][];
			for (int i = 0; i < data.size(); i++) {
				JSONObject item = data.getJSONObject(i);
				JSONArray vec = item.getJSONArray("embedding");
				float[] f = new float[vec.size()];
				for (int j = 0; j < vec.size(); j++) {
					f[j] = vec.getFloatValue(j);
				}
				ordered[item.getIntValue("index")] = f;
			}
			List<float[]> out = new ArrayList<>(batch.size());
			for (float[] f : ordered) {
				if (f == null) {
					throw new BusinessException("百炼 embedding 响应缺项");
				}
				out.add(f);
			}
			return out;
		} catch (BusinessException e) {
			throw e;
		} catch (Exception e) {
			throw new BusinessException("百炼 embedding 响应解析失败：" + e.getMessage());
		}
	}

	/** 组装消息体 {role, content} */
	private JSONObject msg(String role, String content) {
		JSONObject m = new JSONObject();
		m.put("role", role);
		m.put("content", content);
		return m;
	}

	/** 取 key，未配置抛业务异常（调用方是「必须有 key 才能跑」的路径） */
	private String requireApiKey() {
		String key = apiKeyOrNull();
		if (key == null) {
			throw new BusinessException("未配置百炼 API Key（系统配置项 " + OPTION_API_KEY + "）");
		}
		return key;
	}

	/**
	 * POST JSON（Authorization: Bearer）
	 * <p>
	 * 超时策略：连接 5s / 响应 60s（embedding 批量与 LLM 生成都可能到几十秒）。
	 */
	private JSONObject postJson(String apiKey, String url, JSONObject body) {
		// 注意①：不能 try-with-resources 包 httpClient()——那会在请求结束 close 掉单例客户端
		// 注意②：响应体必须在 response handler 内读完（handler 返回后 response 已自动关闭，
		//         把 entity 拿到外面再读会报 "Attempted read on closed stream"）
		try {
			CloseableHttpClient client = httpClient();
			HttpPost post = new HttpPost(new URI(url));
			post.setHeader("Authorization", "Bearer " + apiKey);
			post.setHeader("Content-Type", "application/json");
			post.setEntity(new StringEntity(body.toJSONString(), StandardCharsets.UTF_8));
			String text = client.execute(post, resp -> {
				HttpEntity entity = resp.getEntity();
				return entity == null ? "" : new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8);
			});
			JSONObject json = JSONObject.parseObject(text);
			// OpenAI 兼容错误体：{"error":{"message":...,"code":...}}
			if (json != null && json.containsKey("error")) {
				String errMsg = json.getJSONObject("error").getString("message");
				log.warn("百炼接口报错 url={} msg={}", url, errMsg);
				throw new BusinessException("百炼接口错误：" + errMsg);
			}
			if (json == null || !json.containsKey("data") && !json.containsKey("choices")) {
				throw new BusinessException("百炼接口响应异常：" + text.substring(0, Math.min(200, text.length())));
			}
			return json;
		} catch (IOException | URISyntaxException e) {
			log.warn("百炼接口请求失败 url={}: {}", url, e.getMessage());
			throw new BusinessException("百炼接口请求失败：" + e.getMessage());
		}
	}

	/** 懒加载 HTTP 客户端（连接 5s / 响应 60s） */
	private CloseableHttpClient httpClient() {
		if (httpClient == null) {
			synchronized (this) {
				if (httpClient == null) {
					RequestConfig config = RequestConfig.custom()
							.setConnectTimeout(Timeout.ofSeconds(5))
							.setResponseTimeout(Timeout.ofSeconds(60))
							.build();
					httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
				}
			}
		}
		return httpClient;
	}
}
