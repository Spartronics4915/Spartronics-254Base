package com.spartronics4915.frc2019.subsystems;

import com.spartronics4915.frc2019.Constants;
import com.spartronics4915.frc2019.loops.Loop;
import com.spartronics4915.frc2019.loops.Looper;
import com.spartronics4915.lib.util.Logger;

import edu.wpi.first.wpilibj.Relay;
import edu.wpi.first.wpilibj.SerialPort;
import edu.wpi.first.wpilibj.Timer;

/**
 * The LED subsystem consists of:
 * - DriverLED: for communicating information to drivers (also on dashboard)
 * - VisionLamp: a relay to turn on or off the vision headlights (used for
 * illuminating vision targets).
 * - Bling: a serial port that sends the desired BlingState to the Arduino.
 * - potential extensions:
 * - LED light or strip for communicating with human player
 * - LED light or strip to communicate distance to driver
 * 
 * The main things this subsystem has to do is turn each light on, off, or make
 * it blink.
 * The vision lamp is not subject to blinking and is currently not part of the
 * SystemState.
 * 
 * 
 * Since we have independent components, we have multiple wanted states and
 * system states.
 * 
 * @see Subsystem.java
 */
public class LED extends Subsystem
{

    public static final int kDefaultBlinkCount = 4;
    public static final double kDefaultBlinkDuration = 0.2; // seconds for full cycle
    private static final double kDefaultTotalBlinkDuration =
            kDefaultBlinkCount * kDefaultBlinkDuration;
    
    private static LED mInstance = null;

    public static LED getInstance()
    {
        if (mInstance == null)
        {
            mInstance = new LED();
        }
        return mInstance;
    }

    // Internal state of the DriverLED
    public enum SystemState
    {
        OFF, FIXED_ON, BLINKING, RANGE_FINDING
    }

    public enum WantedState
    {
        OFF, FIXED_ON, BLINK, FIND_RANGE, WARN
    }

    public enum BlingState
    {
        OFF, SCISSOR_OFF, SCISSOR_SWITCH, TELEOP, ENDGAME, SCISSOR_SCALE, CLIMB, OPEN_HARVESTER, CLOSE_HARVESTER, EJECT_HARVESTER, CARRY_CUBE, STOP_CLIMBER
    }

    private SystemState mSystemState = SystemState.OFF;
    private WantedState mWantedState = WantedState.OFF;
    private BlingState mBlingState = BlingState.OFF;

    private boolean mIsLEDOn, mIsLampOn;
    private Relay mDriverLED;
    private Relay mVisionLamp;
    private SerialPort mBling;
    private boolean mIsBlinking = false;
    private double mBlinkDuration;
    private int mBlinkCount;
    private double mTotalBlinkDuration;
    
    /*
     *0 : OFF(GREY)
     *1 : ?
     *2 : ?
     *3 : ?
     *4 : ?
     *5 : ?
     *6 : ?
     *7 : ?
     *8 : ?
     *9 : ?
     */

    private final byte[] kOff = "0".getBytes();                //GREY
    private final byte[] kScissorOff = "1".getBytes();         //YELLOW/BLUE ALTERNATING || FADE BLUE INTO YELLOW AND BACK
    private final byte[] kScissorSwitch = "2".getBytes();      //YELLOW
    private final byte[] kScissorScale = "3".getBytes();       //FLASHING YELLOW
    private final byte[] kTeleop = "4".getBytes();             //FADE GREEN
    private final byte[] kEndGame = "5".getBytes();            //FLASHING GREEN
    private final byte[] kClimb = "6".getBytes();              //FLASHING RED
    private final byte[] kStopClimber = "7".getBytes();        //RED
    private final byte[] kOpenHarvester = "8".getBytes();      //BLUE
    private final byte[] kCloseHarvester = "9".getBytes();     //FLASHING BLUE
    private final byte[] kEjectHarvester = "10".getBytes();    //FADE BLUE
    private final byte[] kCarryCube = "11".getBytes();         //YELLOW CHASING BLUE || FADE YELLOW
    
    private LED()
    {
        boolean success = true;

        try
        {
            mDriverLED = new Relay(Constants.kLEDDriverLEDId);
            setDriverLEDOff();

            mVisionLamp = new Relay(Constants.kLEDVisionLampId);
            setVisionLampOff();

            configureBlink(kDefaultBlinkCount, kDefaultBlinkDuration);

            mBling = new SerialPort(9600, SerialPort.Port.kUSB);
            setBlingState(BlingState.OFF);
            
        }
        catch (Exception e)
        {
            logError("Couldn't instantiate hardware objects");
            Logger.logThrowableCrash(e);
            success = false;
        }

        logInitialized(success);
    }

    private Loop mLoop = new Loop()
    {

        private double mCurrentStateStartTime;

        @Override
        public void onStart(double timestamp)
        {
            synchronized (LED.this)
            {
                mSystemState = SystemState.OFF;
                mWantedState = WantedState.OFF;
                handleOff();
                mIsBlinking = false;
            }

            mCurrentStateStartTime = timestamp;
        }

        @Override
        public void onLoop(double timestamp)
        {
            synchronized (LED.this)
            {
                SystemState newState;
                double timeInState = timestamp - mCurrentStateStartTime;
                switch (mSystemState)
                {
                    case OFF:
                        newState = handleOff();
                        break;
                    case FIXED_ON:
                        newState = handleFixedOn();
                        break;
                    case BLINKING:
                        newState = handleBlinking(timeInState);
                        break;
                    case RANGE_FINDING:
                        newState = handleRangeFinding(timeInState);
                        break;
                    default:
                        logError("Fell through on LED states!!");
                        newState = SystemState.OFF;
                }
                if (newState != mSystemState)
                {
                    logInfo("LED state " + mSystemState + " to " + newState);
                    mSystemState = newState;
                    mCurrentStateStartTime = timestamp;
                }
            }
        }

        @Override
        public void onStop(double timestamp)
        {
            handleOff();
        }
    };

    private SystemState defaultStateTransfer()
    {
        switch (mWantedState)
        {
            case OFF:
                return SystemState.OFF;
            case BLINK:
                return SystemState.BLINKING;
            case WARN:
            case FIND_RANGE:
                return SystemState.RANGE_FINDING;
            case FIXED_ON:
                return SystemState.FIXED_ON;
            default:
                return SystemState.OFF;
        }
    }

    private synchronized SystemState handleOff()
    {
        setDriverLEDOff();
        //setVisionLampOff();
        //We do NOT want the vision lED to be turned off by default
        return defaultStateTransfer();
    }

    private synchronized SystemState handleFixedOn()
    {
        setDriverLEDOn();
        return defaultStateTransfer();
    }

    private synchronized SystemState handleRangeFinding(double timeInState)
    {
        return performBlinking(timeInState);
    }

    private synchronized SystemState handleBlinking(double timeInState)
    {
        return performBlinking(timeInState);
    }

    private SystemState performBlinking(double timeInState)
    {
        if (timeInState > mTotalBlinkDuration)
        {
            setDriverLEDOff();
            // Transition to OFF state and clear wanted state.
            mWantedState = WantedState.OFF;
            return SystemState.OFF;
        }

        int cycleNum = (int) (timeInState / (mBlinkDuration / 2.0));
        if ((cycleNum % 2) == 0)
        {
            setDriverLEDOn();
        }
        else
        {
            setDriverLEDOff();
        }
        return SystemState.BLINKING;
    }

    @Override
    public void outputToSmartDashboard()
    {
        if (!isInitialized())
            return;
        dashboardPutString("BlingState", mBlingState.toString());
    }

    @Override
    public void stop()
    {

    }

    @Override
    public void zeroSensors()
    {

    }

    @Override
    public void registerEnabledLoops(Looper enabledLooper)
    {
        if (!this.isInitialized())
            return;
        enabledLooper.register(mLoop);
    }

    // when setWantedState is invoked, we merely trigger a behavior change
    // in looper since it calls defaultStateChange through each of the handlers.
    public synchronized void setWantedState(WantedState state)
    {
        mWantedState = state;
        dashboardPutState(state.toString());
        dashboardPutString("Message", "");
        switch (mWantedState)
        {
            case OFF:
                break;
            case BLINK:
                configureBlink(kDefaultBlinkCount, kDefaultBlinkDuration);
                break;
            case FIND_RANGE:
            case WARN:
                configureBlink(kDefaultBlinkCount * 2, .5 * kDefaultBlinkDuration);
                break;
            case FIXED_ON:
                break;
            default:
                break;
        }
    }

    public synchronized void setBlingState(BlingState b)
    {
        if (!isInitialized())
            return;
        if (mBlingState != b)
        {
            switch (b)
            {
                case OFF:
                    mBling.write(kOff, kOff.length);
                    break;
                case SCISSOR_OFF:
                    mBling.write(kScissorOff, kScissorOff.length);
                    break;
                case SCISSOR_SWITCH:
                    mBling.write(kScissorSwitch, kScissorSwitch.length);
                    break;
                case SCISSOR_SCALE:
                    mBling.write(kScissorScale, kScissorScale.length);
                    break;
                case TELEOP:
                    mBling.write(kTeleop, kTeleop.length);
                    break;
                case ENDGAME:
                    mBling.write(kEndGame, kEndGame.length);
                    break;
                case CLIMB:
                    mBling.write(kClimb, kClimb.length);
                    break;
                case STOP_CLIMBER:
                    mBling.write(kStopClimber, kStopClimber.length);
                    break;
                case OPEN_HARVESTER:
                    mBling.write(kOpenHarvester, kOpenHarvester.length);
                    break;
                case CLOSE_HARVESTER:
                    mBling.write(kCloseHarvester, kCloseHarvester.length);
                    break;
                case EJECT_HARVESTER:
                    mBling.write(kEjectHarvester, kEjectHarvester.length);
                    break;
                case CARRY_CUBE:
                    mBling.write(kCarryCube, kCarryCube.length);
                    break;
                default:
                    mBling.write(kOff, kOff.length);
                    break;
            }
            mBlingState = b;
        }
    }

    public synchronized BlingState getBlingState()
    {
        return mBlingState;
    }

    public synchronized void warnDriver(String msg)
    {
        setWantedState(WantedState.WARN);
        dashboardPutString("Message", msg);
    }

    public synchronized void setDriverLEDOn()
    {
        if (!mIsLEDOn)
        {
            dashboardPutBoolean("DriverLED", true);
            mIsLEDOn = true;
            mDriverLED.set(Relay.Value.kForward);
        }
    }

    public synchronized void setDriverLEDOff()
    {
        if (mIsLEDOn)
        {
            dashboardPutBoolean("DriverLED", false);
            mIsLEDOn = false;
            mDriverLED.set(Relay.Value.kOff);
        }
    }

    public synchronized boolean getVisionLampState()
    {
        return mIsLampOn;
    }

    public synchronized void setVisionLampOn()
    {
        if (!mIsLampOn)
        {
            mIsLampOn = true;
            dashboardPutBoolean("VisionLamp", mIsLampOn);
            mVisionLamp.set(Relay.Value.kForward);
        }
    }

    public synchronized void setVisionLampOff()
    {
        if (mIsLampOn)
        {
            mIsLampOn = false;
            dashboardPutBoolean("VisionLamp", mIsLampOn);
            mVisionLamp.set(Relay.Value.kOff);
        }
    }

    public synchronized void configureBlink(int blinkCount, double blinkDuration)
    {
        mBlinkDuration = blinkDuration;
        mBlinkCount = blinkCount;
        mTotalBlinkDuration = mBlinkCount * mBlinkDuration;
    }

    @Override
    public boolean checkSystem(String variant)
    {
        logNotice("checkSystem ---------------");
        logNotice("VisionLamp On");
        setVisionLampOn();
        Timer.delay(2.0);
        logNotice("VisionLamp Off");
        setVisionLampOff();
        return true;
    }
}
