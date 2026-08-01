package io.github.jaypetez.ollamamobile.remote

/**
 * The `/v1` surface, for a server that speaks OpenAI's protocol rather than
 * Ollama's own.
 *
 * That is llama.cpp's `llama-server`, vLLM, LM Studio, a gateway — and Ollama
 * itself, which serves both. The native API is preferred whenever it answers
 * (see `ServerClientFactory`) because `/v1` cannot express `keep_alive`,
 * `num_ctx`, the full options block, or the timing statistics that make
 * tokens-per-second a measurement rather than a guess.
 *
 * The interface is [RemoteChatClient] and nothing more. It deliberately does
 * not repeat the Ollama-only methods with "not supported" implementations: a
 * caller that needs `/api/ps` needs an [OllamaClient], and finding that out at
 * compile time beats finding it out from an error at runtime.
 */
interface OpenAiCompatClient : RemoteChatClient
