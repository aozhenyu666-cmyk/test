"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PATHS = void 0;
const ROOT = "/sdcard/Download/Operit";
exports.PATHS = {
    taskState: `${ROOT}/drift/task_state.txt`,
    eventsDir: `${ROOT}/events`,
    execRules: `${ROOT}/events/EXEC_RULES.tsv`,
    activeRules: `${ROOT}/events/ACTIVE_RULES.md`,
    actionLog: `${ROOT}/events/ACTION_LOG.tsv`,
    progressDir: `${ROOT}/progress`,
};
