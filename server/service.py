"""Frame: metadata-only processing state simulator; images stay on device."""

import uuid
from base import BaseService, Problem, fields, integer, text, require, run


class Service(BaseService):
    KIND = "frame"

    def seed(self):
        return {"jobs": []}

    def view(self, state, now):
        return state

    def apply(self, state, action, args, now):
        if action == "register":
            fields(args, {"assetId", "width", "height", "sha256"})
            key = text(args["assetId"], 36)
            try:
                require(str(uuid.UUID(key)) == key, "Canonical asset ID required")
            except ValueError:
                raise Problem("Invalid asset ID")
            width = integer(args["width"], 1, 10000)
            height = integer(args["height"], 1, 10000)
            sha = text(args["sha256"], 64)
            require(len(sha) == 64 and all(c in "0123456789abcdef" for c in sha), "Invalid digest")
            old = next((j for j in state["jobs"] if j["assetId"] == key), None)
            if old:
                require(
                    (old["width"], old["height"], old["sha256"]) == (width, height, sha),
                    "Asset identity changed",
                )
                return {"accepted": True}
            if len(state["jobs"]) >= 20:
                return {"accepted": False, "reason": "Fixture job limit reached"}
            state["jobs"].append(dict(args, generation=1, status="QUEUED"))
            return {"accepted": True}
        fields(args, {"assetId", "generation"})
        key = text(args["assetId"], 36)
        generation = integer(args["generation"], 1)
        row = next((j for j in state["jobs"] if j["assetId"] == key), None)
        if not row or row["generation"] != generation:
            return {"accepted": False, "reason": "Processing generation is no longer current"}
        if action == "retry":
            if row["status"] != "CANCELLED":
                return {"accepted": False, "reason": "Only a cancelled job can be retried"}
            row["generation"] += 1
            row["status"] = "QUEUED"
            return {"accepted": True}
        if row["status"] != "QUEUED":
            return {"accepted": False, "reason": "Processing job is terminal"}
        if action == "cancel":
            row["status"] = "CANCELLED"
        elif action == "finish":
            row["status"] = "COMPLETE"
        else:
            raise Problem("Unknown action")
        return {"accepted": True}


if __name__ == "__main__":
    run(Service, 8923)
