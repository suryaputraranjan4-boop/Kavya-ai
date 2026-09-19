sed -i 's/VoiceState.THINKING -> 1.04f/VoiceState.UNDERSTANDING -> 1.02f\n        VoiceState.THINKING -> 1.04f\n        VoiceState.EXECUTING -> 1.06f/g' app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt

sed -i 's/VoiceState.THINKING -> stateScale \* idleBreath \* pressScale/VoiceState.UNDERSTANDING -> stateScale \* idleBreath \* pressScale\n        VoiceState.THINKING -> stateScale \* idleBreath \* pressScale\n        VoiceState.EXECUTING -> stateScale \* speakingPulse \* pressScale/g' app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt
