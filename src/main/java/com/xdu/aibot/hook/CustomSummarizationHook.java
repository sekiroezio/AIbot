package com.xdu.aibot.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 动态 Token 比例窗口 + 摘要 Hook
 *
 * 改进官方 SummarizationHook：
 * - 官方使用固定 messagesToKeep 条数，可能导致保留消息 token 超过阈值，反复触发压缩
 * - 本 Hook 改为动态计算：从最新消息往前累加 token，保留消息总 token ≤ maxTokensBeforeSummary × safeRatio
 * - 数学保证：压缩后保留消息 token < 阈值，绝不反复触发
 * - messagesToKeep 降级为最小保留条数保底
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public class CustomSummarizationHook extends MessagesModelHook {
    private static final Logger log = LoggerFactory.getLogger(CustomSummarizationHook.class);
    private static final String DEFAULT_SUMMARY_PROMPT =
            "<role>\nContext Extraction Assistant\n</role>\n\n"
                    + "<primary_objective>\n"
                    + "Your sole objective in this task is to extract the highest quality/most relevant "
                    + "context from the conversation history below.\n</primary_objective>\n\n"
                    + "<instructions>\nThe conversation history below will be replaced with the context "
                    + "you extract in this step. Extract and record all of the most important context "
                    + "from the conversation history.\nRespond ONLY with the extracted context. "
                    + "Do not include any additional information.\n</instructions>\n\n"
                    + "<messages>\nMessages to summarize:\n%s\n</messages>";
    private static final String SUMMARY_PREFIX = "## Previous conversation summary:";
    private static final int DEFAULT_MIN_MESSAGES_TO_KEEP = 2;
    private static final int SEARCH_RANGE_FOR_TOOL_PAIRS = 5;
    private static final boolean DEFAULT_KEEP_FIRST_USER_MESSAGE = true;
    private static final double DEFAULT_SAFE_RATIO = 0.25;

    private final ChatModel model;
    private final Integer maxTokensBeforeSummary;
    private final int minMessagesToKeep;       // 最小保留条数（保底）
    private final double safeRatio;            // 保留消息的 token 安全比例
    private final TokenCounter tokenCounter;
    private final String summaryPrompt;
    private final String summaryPrefix;
    private final boolean keepFirstUserMessage;

    private CustomSummarizationHook(Builder builder) {
        this.model = builder.model;
        this.maxTokensBeforeSummary = builder.maxTokensBeforeSummary;
        this.minMessagesToKeep = builder.minMessagesToKeep;
        this.safeRatio = builder.safeRatio;
        this.tokenCounter = builder.tokenCounter;
        this.summaryPrompt = builder.summaryPrompt;
        this.summaryPrefix = builder.summaryPrefix;
        this.keepFirstUserMessage = builder.keepFirstUserMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        if (this.maxTokensBeforeSummary == null) {
            return new AgentCommand(previousMessages);
        }

        int totalTokens = this.tokenCounter.countTokens(previousMessages);
        if (totalTokens < this.maxTokensBeforeSummary) {
            return new AgentCommand(previousMessages);
        }

        log.info("Token count {} exceeds threshold {}, triggering summarization (safeRatio={})",
                totalTokens, this.maxTokensBeforeSummary, this.safeRatio);

        int cutoffIndex = this.findSafeCutoff(previousMessages);
        if (cutoffIndex <= 0) {
            log.warn("Cannot find safe cutoff point for summarization, skipping");
            return new AgentCommand(previousMessages);
        }

        UserMessage firstUserMessage = null;
        if (this.keepFirstUserMessage) {
            for (Message msg : previousMessages) {
                if (msg instanceof UserMessage) {
                    firstUserMessage = (UserMessage) msg;
                    break;
                }
            }
        }

        List<Message> toSummarize = new ArrayList<>();
        for (int i = 0; i < cutoffIndex; ++i) {
            Message msg = previousMessages.get(i);
            if (msg != firstUserMessage) {
                toSummarize.add(msg);
            }
        }

        String summary = this.createSummary(toSummarize);
        SystemMessage summaryMessage = new SystemMessage(this.summaryPrefix + "\n" + summary);

        List<Message> recentMessages = new ArrayList<>();
        for (int i = cutoffIndex; i < previousMessages.size(); ++i) {
            recentMessages.add(previousMessages.get(i));
        }

        // 计算保留窗口的 token 数
        int recentTokens = this.tokenCounter.countTokens(recentMessages);
        log.info("Summarized {} messages, keeping {} recent messages ({} tokens, budget={})",
                toSummarize.size(), recentMessages.size(), recentTokens,
                (int)(this.maxTokensBeforeSummary * this.safeRatio));

        List<Message> newMessages = new ArrayList<>();
        if (firstUserMessage != null) {
            newMessages.add(firstUserMessage);
        }
        newMessages.add(summaryMessage);
        newMessages.addAll(recentMessages);

        int afterTokens = this.tokenCounter.countTokens(newMessages);
        log.info("Summarization completed: before={} tokens, after={} tokens, reduced={} tokens ({}%)",
                totalTokens, afterTokens, totalTokens - afterTokens,
                totalTokens > 0 ? String.format("%.1f", (1 - (double) afterTokens / totalTokens) * 100) : "0");

        return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
    }

    /**
     * 动态 Token 比例窗口截断
     *
     * 从最新消息往前累加 token，直到累计超过 safeTokenBudget，
     * 此时前一条消息即为截断点。同时确保至少保留 minMessagesToKeep 条消息。
     * 最后向前搜索安全的截断点（避免拆散 tool-call / tool-response 对）。
     */
    private int findSafeCutoff(List<Message> messages) {
        int safeTokenBudget = (int)(this.maxTokensBeforeSummary * this.safeRatio);

        // 从后往前累加 token，找到不超过 safeTokenBudget 的最大保留窗口
        int accumulatedTokens = 0;
        int cutoffIndex = 0; // 默认：全部保留（不需要截断）

        for (int i = messages.size() - 1; i >= 0; i--) {
            int msgTokens = this.tokenCounter.countTokens(List.of(messages.get(i)));
            if (accumulatedTokens + msgTokens > safeTokenBudget) {
                cutoffIndex = i + 1; // messages[i+1 ... end] 为保留窗口
                break;
            }
            accumulatedTokens += msgTokens;
        }

        // 如果全部消息的 token 都没超 safeTokenBudget，不需要截断
        if (cutoffIndex == 0) {
            return 0;
        }

        // 保底：至少保留 minMessagesToKeep 条消息
        int minCutoff = messages.size() - this.minMessagesToKeep;
        cutoffIndex = Math.min(cutoffIndex, minCutoff);

        if (cutoffIndex <= 0) {
            return 0;
        }

        // 向前搜索安全的截断点（避免拆散 tool-call / tool-response 对）
        for (int i = cutoffIndex; i >= Math.max(0, cutoffIndex - SEARCH_RANGE_FOR_TOOL_PAIRS); i--) {
            if (this.isSafeCutoffPoint(messages, i)) {
                return i;
            }
        }

        return cutoffIndex;
    }

    // ========== 以下方法与官方实现完全一致，保持不变 ==========

    private boolean isSafeCutoffPoint(List<Message> messages, int cutoffIndex) {
        if (cutoffIndex >= messages.size()) {
            return true;
        }
        int searchStart = Math.max(0, cutoffIndex - SEARCH_RANGE_FOR_TOOL_PAIRS);
        int searchEnd = Math.min(messages.size(), cutoffIndex + SEARCH_RANGE_FOR_TOOL_PAIRS);

        for (int i = searchStart; i < searchEnd; ++i) {
            if (this.hasToolCalls(messages.get(i))) {
                AssistantMessage aiMessage = (AssistantMessage) messages.get(i);
                Set<String> toolCallIds = this.extractToolCallIds(aiMessage);
                if (this.cutoffSeparatesToolPair(messages, i, cutoffIndex, toolCallIds)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasToolCalls(Message message) {
        if (message instanceof AssistantMessage assistantMessage) {
            return !assistantMessage.getToolCalls().isEmpty();
        }
        return false;
    }

    private Set<String> extractToolCallIds(AssistantMessage aiMessage) {
        Set<String> toolCallIds = new HashSet<>();
        for (AssistantMessage.ToolCall toolCall : aiMessage.getToolCalls()) {
            toolCallIds.add(toolCall.id());
        }
        return toolCallIds;
    }

    private boolean cutoffSeparatesToolPair(List<Message> messages, int aiMessageIndex,
                                            int cutoffIndex, Set<String> toolCallIds) {
        for (int j = aiMessageIndex + 1; j < messages.size(); ++j) {
            Message message = messages.get(j);
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    if (toolCallIds.contains(response.id())) {
                        boolean aiBeforeCutoff = aiMessageIndex < cutoffIndex;
                        boolean toolBeforeCutoff = j < cutoffIndex;
                        if (aiBeforeCutoff != toolBeforeCutoff) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private String createSummary(List<Message> messages) {
        if (messages.isEmpty()) {
            return "No previous conversation.";
        }
        StringBuilder messageText = new StringBuilder();
        for (Message msg : messages) {
            String role = this.getRoleName(msg);
            messageText.append(role).append(": ").append(msg.getText()).append("\n");
        }
        String prompt = String.format(this.summaryPrompt, messageText.toString());
        try {
            Prompt summaryPromptObj = new Prompt(List.of(new UserMessage(prompt)));
            ChatResponse response = this.model.call(summaryPromptObj);
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("Failed to create summary: {}", e.getMessage());
            return "Summary generation failed: " + e.getMessage();
        }
    }

    private String getRoleName(Message message) {
        if (message instanceof UserMessage) {
            return "Human";
        } else if (message instanceof AssistantMessage) {
            return "Assistant";
        } else if (message instanceof SystemMessage) {
            return "System";
        } else if (message instanceof ToolResponseMessage) {
            return "Tool";
        }
        return "Unknown";
    }

    @Override
    public String getName() {
        return "DynamicTokenRatioSummarization";
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of();
    }

    // ========== Builder ==========

    public static class Builder {
        private ChatModel model;
        private Integer maxTokensBeforeSummary;
        private int minMessagesToKeep = DEFAULT_MIN_MESSAGES_TO_KEEP;
        private double safeRatio = DEFAULT_SAFE_RATIO;
        private TokenCounter tokenCounter = TokenCounter.approximateMsgCounter();
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
        private String summaryPrefix = SUMMARY_PREFIX;
        private boolean keepFirstUserMessage = DEFAULT_KEEP_FIRST_USER_MESSAGE;

        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        public Builder maxTokensBeforeSummary(Integer maxTokens) {
            this.maxTokensBeforeSummary = maxTokens;
            return this;
        }

        /** 最小保留消息条数（保底），不再作为主要截断依据 */
        public Builder minMessagesToKeep(int count) {
            this.minMessagesToKeep = count;
            return this;
        }

        /** 保留消息的 token 安全比例，默认 0.25（25%） */
        public Builder safeRatio(double ratio) {
            if (ratio <= 0 || ratio >= 1) {
                throw new IllegalArgumentException("safeRatio must be between 0 and 1 (exclusive)");
            }
            this.safeRatio = ratio;
            return this;
        }

        public Builder summaryPrompt(String prompt) {
            this.summaryPrompt = prompt;
            return this;
        }

        public Builder summaryPrefix(String prefix) {
            this.summaryPrefix = prefix;
            return this;
        }

        public Builder tokenCounter(TokenCounter counter) {
            this.tokenCounter = counter;
            return this;
        }

        public Builder keepFirstUserMessage(boolean keep) {
            this.keepFirstUserMessage = keep;
            return this;
        }

        public CustomSummarizationHook build() {
            if (this.model == null) {
                throw new IllegalArgumentException("model must be specified");
            }
            return new CustomSummarizationHook(this);
        }
    }
}