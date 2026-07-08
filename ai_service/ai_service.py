from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Dict, Optional
from datetime import datetime, date, timedelta
import random

app = FastAPI(
    title="LifeFlow AI Microservice",
    description="AI endpoints for predicting demand, matching donors, and analyzing donor engagement.",
    version="1.0.0"
)

# Models for Predict Demand
class BloodRequestHistory(BaseModel):
    blood_group: str
    units: int
    created_at: str # ISO format or timestamp

class PredictDemandRequest(BaseModel):
    historical_requests: List[BloodRequestHistory]

class PredictDemandResponse(BaseModel):
    predicted_demand_next_14_days: Dict[str, int]

# Models for Optimize Match
class BloodRequest(BaseModel):
    request_id: int
    blood_group: str
    units: int
    urgency: Optional[str] = "NORMAL"

class Donor(BaseModel):
    donor_id: int
    blood_group: str
    city: str

class OptimizeMatchRequest(BaseModel):
    request: BloodRequest
    donors: List[Donor]

class MatchedDonor(BaseModel):
    donor_id: int
    compatibility_score: int
    priority_ranking: int

# Models for Donor Engagement
class DonorEngagementRequest(BaseModel):
    donor_id: int
    last_donation_date: Optional[str] # ISO date format (YYYY-MM-DD), None if first time
    total_donations: int

class DonorEngagementResponse(BaseModel):
    eligibility_countdown_days: int
    churn_risk_percent: int


@app.post("/ai/predict-demand", response_model=PredictDemandResponse)
def predict_demand(payload: PredictDemandRequest):
    """
    Applies a simple predictive model to forecast blood demand for the next 14 days 
    based on historical requests.
    """
    demand_by_group = {
        "A+": 0, "A-": 0, "B+": 0, "B-": 0,
        "AB+": 0, "AB-": 0, "O+": 0, "O-": 0
    }
    
    # Simple logic: calculate historical run rate and project it 14 days forward, adding some variance
    for req in payload.historical_requests:
        bg = req.blood_group.upper()
        if bg in demand_by_group:
            demand_by_group[bg] += req.units
            
    # Mocking a forecast by boosting based on historical volume + 10% safety buffer
    forecast = {}
    for bg, hist_units in demand_by_group.items():
        base_forecast = max(5, int(hist_units * 1.1)) # Minimum 5 units forecast
        forecast[bg] = base_forecast
        
    return PredictDemandResponse(predicted_demand_next_14_days=forecast)


def check_compatibility(patient_bg: str, donor_bg: str) -> bool:
    """Returns True if donor_bg can donate to patient_bg"""
    patient_can_receive_from = {
        "A+": ["A+", "A-", "O+", "O-"],
        "A-": ["A-", "O-"],
        "B+": ["B+", "B-", "O+", "O-"],
        "B-": ["B-", "O-"],
        "AB+": ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"],
        "AB-": ["AB-", "A-", "B-", "O-"],
        "O+": ["O+", "O-"],
        "O-": ["O-"]
    }
    patient_bg = patient_bg.upper()
    donor_bg = donor_bg.upper()
    
    if patient_bg in patient_can_receive_from and donor_bg in patient_can_receive_from[patient_bg]:
        return True
    return False

@app.post("/ai/optimize-match", response_model=List[MatchedDonor])
def optimize_match(payload: OptimizeMatchRequest):
    """
    Evaluates donors against a specific request to calculate compatibility and priority rankings.
    Returns a sorted list of compatible donors.
    """
    patient_bg = payload.request.blood_group
    matched_donors = []
    
    for donor in payload.donors:
        if check_compatibility(patient_bg, donor.blood_group):
            # Calculate a base score
            # Exact match gets 100, compatible but different gets 80
            score = 100 if patient_bg.upper() == donor.blood_group.upper() else 80
            
            # Priority bump based on deterministic attributes (e.g. city match if passed)
            city_hash_bonus = (hash(donor.city) % 10)
            priority = score + city_hash_bonus
            
            matched_donors.append(
                MatchedDonor(
                    donor_id=donor.donor_id,
                    compatibility_score=score,
                    priority_ranking=priority
                )
            )
            
    # Sort by priority ranking descending
    matched_donors.sort(key=lambda x: x.priority_ranking, reverse=True)
    return matched_donors


@app.post("/ai/donor-engagement", response_model=DonorEngagementResponse)
def evaluate_donor_engagement(payload: DonorEngagementRequest):
    """
    Evaluates a donor's record to calculate an 'eligibility countdown' 
    and 'churn risk' using basic heuristics.
    """
    # Eligibility rules (e.g., 56 days between whole blood donations)
    required_gap_days = 56
    eligibility_countdown = 0
    churn_risk = 50 # Default middle risk
    
    if payload.last_donation_date:
        try:
            # Handle timestamps with time components or just dates
            date_str = payload.last_donation_date.split(" ")[0]
            last_date = datetime.strptime(date_str, "%Y-%m-%d").date()
            days_since_last = (date.today() - last_date).days
            
            if days_since_last < required_gap_days:
                eligibility_countdown = required_gap_days - days_since_last
            else:
                eligibility_countdown = 0
                
            # Churn risk increases if they haven't donated in a long time
            if days_since_last > 180:
                churn_risk = min(95, 50 + int((days_since_last - 180) / 10))
            else:
                churn_risk = max(5, 50 - int(payload.total_donations * 5))
                
        except ValueError:
            # fallback if date is unparseable
            pass
    else:
        # Never donated, no countdown, high churn risk (might never start)
        churn_risk = 70
        
    return DonorEngagementResponse(
        eligibility_countdown_days=eligibility_countdown,
        churn_risk_percent=churn_risk
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
