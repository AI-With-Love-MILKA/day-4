## Day 4: Temperature Demo

Goal: run the same user prompt through OpenAI Responses API with three temperatures and compare:
- `temperature = 0`
- `temperature = 0.7`
- `temperature = 1.2`

### Run

1. Export API key:
   ```bash
   export OPENAI_API_KEY="your_api_key"
   ```
2. Start the app:
   ```bash
   ./gradlew :app:run
   ```
3. Enter one prompt text.

Type `exit` to stop.

### What the app prints

- all 3 full model responses (for each temperature)
- generated comparison report for:
- accuracy
- creativity
- diversity
- recommendations for best use cases per temperature
- animated colorful loading spinner for each API request in console
