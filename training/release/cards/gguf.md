---
license: apache-2.0
base_model: praxis-chess/{{lora_repo_name}}
base_model_relation: quantized
pipeline_tag: text-generation
language:
- en
tags:
- chess
- gguf
- ollama
- llama.cpp
- evidence-grounded
- verifiable
- qwen3.5
---

# Praxis Chess Reasoner: Qwen3.5-{{size}} (GGUF, q4_K_M)

> **v1.0, waiting for an independent reproduction.** See the
> [adapter's card](https://huggingface.co/praxis-chess/{{lora_repo_name}}) for
> what this model does, how it was trained and evaluated, and its limits.

[praxis-chess/{{lora_repo_name}}](https://huggingface.co/praxis-chess/{{lora_repo_name}})
merged into {{base}} (revision `{{base_revision}}`) and quantised to **q4_K_M**
with llama.cpp: {{gguf_mb}} MB, runs on a 4 GB GPU. This is the exact file
every published number was measured with.

| Public test set (838 positions) | Claim precision (95% CI) | Chain validity (95% CI) | Median latency, RTX 3050 4 GB |
|---|---|---|---|
| This file, greedy, answer schema enforced | {{pub_r3_cp}}% ({{pub_r3_cp_ci}}) | {{pub_r3_cv}}% ({{pub_r3_cv_ci}}) | {{pub_r3_latency}} s |

## Run it with Ollama

```bash
hf download praxis-chess/{{repo_name}} --revision v1.0 --local-dir praxis-{{size_lower}}
cd praxis-{{size_lower}}
ollama create praxis-chess-{{size_lower}} -f Modelfile
```

The Modelfile holds the training format exactly: the system message, a user
turn, then an assistant turn whose thinking block is already closed. Use
temperature 0 and stop at `<|im_end|>`. It sets both.

The user message is the **R3 rendering** of an evidence graph. The
[dataset](https://huggingface.co/datasets/praxis-chess/praxis-chess-evidence-graphs)
has one for every public test position (`renders.R3.text`), and Praxis's
`GraphCli` builds them for any position. Do this to get the published setup,
answer schema included:

```python
import json, urllib.request
schema = json.load(open("answer_schema.json"))["json_schema"]   # schema/answer_schema.json in the dataset
prompt = ("<|im_start|>system\n{{system_prompt}}<|im_end|>\n"
          f"<|im_start|>user\n{r3_text}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n")
body = {"model": "praxis-chess-{{size_lower}}", "prompt": prompt, "raw": True, "stream": False, "format": schema,
        "options": {"temperature": 0, "top_p": 1, "seed": 0, "num_ctx": 3072, "num_predict": 1000, "stop": ["<|im_end|>"]}}
req = urllib.request.Request("http://localhost:11434/api/generate", json.dumps(body).encode(),
                             {"Content-Type": "application/json"})
answer = json.load(urllib.request.urlopen(req))["response"]
```

Then check it with `praxis_eval` (see the adapter card) and show it only if it
passes.

## Files

| File | What |
|---|---|
| `{{gguf_file}}` | The merged model, q4_K_M (sha256 `{{gguf_sha256}}`) |
| `Modelfile` | For `ollama create`: template, system message, temperature 0, context 3,072, stop token |
| `run_manifest.json` | Provenance: adapter, base revision, llama.cpp quantisation, hashes |

## Licence

Apache-2.0, like the adapter and the base model ({{base}}, by the Qwen team).
