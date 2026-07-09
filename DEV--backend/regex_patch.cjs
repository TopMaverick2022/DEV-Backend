const fs = require('fs');
const path = 'd:/xampp/htdocs/DeveloperEv/DEV-Backend/DEV--backend/src/main/java/com/developerev/service/AntiGravityService.java';
let content = fs.readFileSync(path, 'utf8');

content = content.replace(/8\. Auto-detect.*?"detectedNeeds"\./, `8. Auto-detect all the specific libraries, dependencies, framework modules, or tools needed for this feature and return them in "detectedNeeds".\n        9. CRITICAL: For the "type" field of each task, you MUST use exactly one of the following predefined strings: "Backend", "Frontend", "Database", "Security", "Testing", "Documentation", "DevOps", "Architecture", "Design". Do NOT invent custom types like "Backend Configuration" or "Database Schema". Use these predefined exact strings ONLY.`);

content = content.replace(/"type": "",/, `"type": "Backend", // MUST be one of the predefined types`);

content = content.replace(/activityLogService\.logCurrentUserActivity\(feature\.getProjectId\(\), "Implemented Plan", "AI implemented codebase for feature: " \+ feature\.getName\(\)\);/, `for (com.developerev.model.Task task : tasks) {\n        task.setStatus(com.developerev.model.TaskStatus.DONE);\n        taskRepository.save(task);\n      }\n\n      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Implemented Plan", "AI implemented codebase for feature: " + feature.getName());`);

fs.writeFileSync(path, content, 'utf8');
console.log('Regex patch complete');
