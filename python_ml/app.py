import os
import time
import hashlib
from typing import Any, Dict, List, Optional

import numpy as np
import joblib
import xgboost as xgb
from fastapi import FastAPI
from pydantic import BaseModel, Field

APP_VERSION = "1.0.0"

def now_ms() -> int:
    return int(time.time() * 1000)

def models_dir() -> str:
    # по умолчанию сохраняем модели в ../ml-models (рядом с проектом)
    d = os.getenv("ML_MODELS_DIR")
    if not d:
        here = os.path.dirname(os.path.abspath(__file__))
        d = os.path.abspath(os.path.join(here, "..", "ml-models"))
    os.makedirs(d, exist_ok=True)
    return d

def safe_key(s: str) -> str:
    return "".join(ch if ch.isalnum() or ch in ("-", "_", "|") else "_" for ch in s)

def sha1(s: str) -> str:
    return hashlib.sha1(s.encode("utf-8")).hexdigest()[:10]

def schema_hash(feature_names: List[str]) -> str:
    return sha1("|".join(feature_names))

def model_filename(model_key: str, schema_h: str) -> str:
    ver = time.strftime("%Y%m%d-%H%M%S")
    return f"{safe_key(model_key)}__{ver}__{schema_h}.joblib"

def latest_model_path(model_key: str, schema_h: str) -> Optional[str]:
    d = models_dir()
    prefix = f"{safe_key(model_key)}__"
    best = None
    for name in os.listdir(d):
        if not name.startswith(prefix):
            continue
        if not name.endswith(".joblib"):
            continue
        if f"__{schema_h}.joblib" not in name:
            continue
        full = os.path.join(d, name)
        if best is None or os.path.getmtime(full) > os.path.getmtime(best):
            best = full
    return best

class TrainRequest(BaseModel):
    modelKey: str
    featureNames: List[str]
    X: List[List[float]]
    y: List[int]
    params: Dict[str, Any] = Field(default_factory=dict)
    meta: Dict[str, Any] = Field(default_factory=dict)

class TrainResponse(BaseModel):
    ok: bool
    modelPath: Optional[str] = None
    modelVersion: Optional[str] = None
    metrics: Dict[str, Any] = Field(default_factory=dict)
    message: str

class PredictRequest(BaseModel):
    modelKey: str
    featureNames: List[str]
    x: List[float]
    meta: Dict[str, Any] = Field(default_factory=dict)

class PredictResponse(BaseModel):
    ok: bool
    score: float = 0.0
    modelVersion: Optional[str] = None
    debug: Dict[str, Any] = Field(default_factory=dict)
    message: str

app = FastAPI(title="XGBoost Sidecar", version=APP_VERSION)

@app.get("/health")
def health():
    return {"ok": True, "ts": now_ms(), "version": APP_VERSION, "modelsDir": models_dir()}

@app.post("/train", response_model=TrainResponse)
def train(req: TrainRequest):
    if not req.modelKey:
        return TrainResponse(ok=False, message="modelKey is empty")
    if len(req.featureNames) == 0:
        return TrainResponse(ok=False, message="featureNames is empty")
    if len(req.X) == 0 or len(req.y) == 0:
        return TrainResponse(ok=False, message="X/y is empty")
    if len(req.X) != len(req.y):
        return TrainResponse(ok=False, message="X and y size mismatch")

    X = np.array(req.X, dtype=np.float32)
    y = np.array(req.y, dtype=np.int32)

    p = {
        "objective": "binary:logistic",
        "eval_metric": "logloss",
        "max_depth": int(req.params.get("max_depth", 5)),
        "eta": float(req.params.get("eta", 0.08)),
        "subsample": float(req.params.get("subsample", 0.9)),
        "colsample_bytree": float(req.params.get("colsample_bytree", 0.9)),
        "min_child_weight": float(req.params.get("min_child_weight", 1.0)),
        "lambda": float(req.params.get("lambda", 1.0)),
        "alpha": float(req.params.get("alpha", 0.0)),
        "seed": int(req.params.get("seed", 42)),
        "tree_method": str(req.params.get("tree_method", "hist")),
    }
    n_estimators = int(req.params.get("n_estimators", 300))

    dtrain = xgb.DMatrix(X, label=y, feature_names=req.featureNames)
    booster = xgb.train(p, dtrain, num_boost_round=n_estimators)

    sh = schema_hash(req.featureNames)
    fname = model_filename(req.modelKey, sh)
    path = os.path.join(models_dir(), fname)

    payload = {
        "booster": booster,
        "featureNames": req.featureNames,
        "schemaHash": sh,
        "modelKey": req.modelKey,
        "createdAtMs": now_ms(),
        "meta": req.meta,
        "params": {**p, "n_estimators": n_estimators},
    }
    joblib.dump(payload, path)

    return TrainResponse(
        ok=True,
        modelPath=path,
        modelVersion=fname,
        metrics={"rows": int(X.shape[0]), "features": int(X.shape[1]), "schemaHash": sh},
        message="trained"
    )

@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    if not req.modelKey:
        return PredictResponse(ok=False, message="modelKey is empty")
    if len(req.featureNames) == 0:
        return PredictResponse(ok=False, message="featureNames is empty")
    if len(req.x) != len(req.featureNames):
        return PredictResponse(ok=False, message="x size != featureNames size")

    sh = schema_hash(req.featureNames)
    path = latest_model_path(req.modelKey, sh)
    if path is None:
        return PredictResponse(ok=False, message="model not found", debug={"schemaHash": sh})

    payload = joblib.load(path)
    feature_names = payload["featureNames"]
    if feature_names != req.featureNames:
        return PredictResponse(ok=False, message="schema mismatch",
                               debug={"expected": feature_names, "got": req.featureNames, "schemaHash": sh})

    booster = payload["booster"]
    X = np.array([req.x], dtype=np.float32)
    dtest = xgb.DMatrix(X, feature_names=req.featureNames)
    score = float(booster.predict(dtest)[0])

    return PredictResponse(ok=True, score=score, modelVersion=os.path.basename(path),
                           debug={"schemaHash": sh}, message="ok")
