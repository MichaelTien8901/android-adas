package com.adasedge.app.warnings

import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.Warning

/**
 * A single ADAS function. Each evaluator subscribes to the shared perception
 * contract plus speed-context and returns any active warnings for this frame
 * (design D6). Stateful evaluators keep their own hysteresis/dwell state.
 */
interface WarningEvaluator {
    fun evaluate(result: PerceptionResult, speed: SpeedSample): List<Warning>
}
