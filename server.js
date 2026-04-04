// ================================================================
//  🚌 Smart Bus Tracking — IIT Ropar
//  v7: Real Gmail OTP verification via Nodemailer
// ================================================================
require('dotenv').config();
'use strict';

const express      = require('express');
const mongoose     = require('mongoose');
const bcrypt       = require('bcryptjs');
const jwt          = require('jsonwebtoken');
const cors         = require('cors');
const nodemailer   = require('nodemailer');

const app = express();
app.use(cors());
app.use(express.json());

// ── CONFIG ────────────────────────────────────────────────────────
const JWT_SECRET    = process.env.JWT_SECRET    || 'iitropar@BusTrack#2024$SecureKey!XyZ';
const MONGO_URI     = process.env.MONGO_URI     || 'mongodb://localhost:27017/bustrack';
const DRIVER_PASS   = process.env.DRIVER_PASS   || 'driver123';
const PORT          = process.env.PORT          || 3000;

// ── Gmail account used to SEND OTP emails ─────────────────────────
// Use a dedicated Gmail account for your app
// Enable "App Passwords" in Google Account → Security → 2-Step Verification → App Passwords
const MAIL_USER     = process.env.MAIL_USER     || 'your.app.email@gmail.com';
const MAIL_PASS     = process.env.MAIL_PASS     || 'your_app_password_here';

const SHARING_TIMEOUT_MS = 2 * 60 * 60 * 1000;

// ── NODEMAILER SETUP ──────────────────────────────────────────────
const transporter = nodemailer.createTransport({
  service: 'gmail',
  auth: {
    user: MAIL_USER,
    pass: MAIL_PASS,
  },
});

// ── MONGODB ───────────────────────────────────────────────────────
mongoose.connect(MONGO_URI)
  .then(() => console.log('✅ MongoDB connected'))
  .catch(err => console.error('❌ MongoDB error:', err));

// ── MODELS ────────────────────────────────────────────────────────
const userSchema = new mongoose.Schema({
  name:         { type: String, required: true },
  email:        { type: String, required: true, unique: true, lowercase: true },
  password:     { type: String, required: true },
  isVerified:   { type: Boolean, default: false },
});
const User = mongoose.model('User', userSchema);

// Temporary OTP store (in-memory is fine — expires in 10 min)
// Structure: { email → { otp, name, password, expiresAt } }
const otpStore = new Map();

const busLocationSchema = new mongoose.Schema({
  busId:            { type: String, required: true, unique: true },
  lat:              Number,
  lng:              Number,
  started:          { type: Boolean, default: false },
  updatedAt:        { type: Date, default: Date.now },
  sharingStartedAt: { type: Date, default: null },
  route:            [{ lat: Number, lng: Number }],
});
const BusLocation = mongoose.model('BusLocation', busLocationSchema);

const stopSchema = new mongoose.Schema({
  name: String, time: String, lat: Number, lng: Number
});
const scheduleSchema = new mongoose.Schema({
  busId:     { type: String, required: true, unique: true },
  title:     String,
  departure: String,
  days:      [String],
  stops:     [stopSchema],
});
const Schedule = mongoose.model('Schedule', scheduleSchema);

// ── HELPERS ───────────────────────────────────────────────────────
function isGmail(email) {
  return typeof email === 'string' && email.toLowerCase().endsWith('@gmail.com');
}

function generateOTP() {
  return Math.floor(100000 + Math.random() * 900000).toString(); // 6 digits
}

function verifyToken(req, res, next) {
  const auth  = req.headers['authorization'] || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'No token' });
  try { req.user = jwt.verify(token, JWT_SECRET); next(); }
  catch { res.status(401).json({ error: 'Invalid or expired token' }); }
}

function isExpired(busLoc) {
  if (!busLoc || !busLoc.started) return true;
  if (!busLoc.sharingStartedAt)   return false;
  return Date.now() - new Date(busLoc.sharingStartedAt).getTime() > SHARING_TIMEOUT_MS;
}

// ── AUTH ──────────────────────────────────────────────────────────

// ── STEP 1: Send OTP to Gmail ──────────────────────────────────────
// POST /sendOtp  →  { name, email, password }
app.post('/sendOtp', async (req, res) => {
  try {
    const { name, email, password } = req.body;

    if (!name || !email || !password)
      return res.status(400).json({ error: 'Fill all fields' });

    if (!isGmail(email))
      return res.status(400).json({ error: 'Only Gmail addresses allowed' });

    if (password.length < 6)
      return res.status(400).json({ error: 'Password must be at least 6 characters' });

    if (await User.findOne({ email: email.toLowerCase() }))
      return res.status(409).json({ error: 'This Gmail is already registered' });

    const otp = generateOTP();
    const expiresAt = Date.now() + 10 * 60 * 1000; // 10 minutes

    // Store temporarily
    otpStore.set(email.toLowerCase(), { otp, name, password, expiresAt });

    // Send OTP email
    await transporter.sendMail({
      from:    `"IIT Ropar Bus Tracker" <${MAIL_USER}>`,
      to:      email,
      subject: '🚌 Your OTP for Smart Bus Tracker',
      html: `
        <div style="font-family:Arial,sans-serif;max-width:480px;margin:auto;
                    border:1px solid #ddd;border-radius:12px;padding:32px;">
          <h2 style="color:#1A237E;text-align:center;">🚌 IIT Ropar Bus Tracker</h2>
          <p style="font-size:16px;color:#333;">Hi <strong>${name}</strong>,</p>
          <p style="font-size:15px;color:#333;">
            Your one-time verification code is:
          </p>
          <div style="text-align:center;margin:24px 0;">
            <span style="font-size:40px;font-weight:bold;
                         letter-spacing:10px;color:#1565C0;">
              ${otp}
            </span>
          </div>
          <p style="font-size:13px;color:#888;">
            This code expires in <strong>10 minutes</strong>.<br/>
            If you did not request this, please ignore this email.
          </p>
          <hr style="margin:24px 0;border:none;border-top:1px solid #eee;"/>
          <p style="font-size:12px;color:#aaa;text-align:center;">
            IIT Ropar Smart Bus Tracking System
          </p>
        </div>
      `,
    });

    console.log(`📧 OTP sent to ${email}`);
    res.json({ message: 'OTP sent to your Gmail. Check your inbox.' });

  } catch (e) {
    console.error('sendOtp error:', e.message);
    // If email sending fails (wrong Gmail, etc.)
    if (e.message.includes('Invalid login') || e.message.includes('auth')) {
      res.status(500).json({ error: 'Email service not configured. Contact admin.' });
    } else {
      res.status(500).json({ error: 'Failed to send OTP: ' + e.message });
    }
  }
});

// ── STEP 2: Verify OTP and create account ─────────────────────────
// POST /verifyOtp  →  { email, otp }
app.post('/verifyOtp', async (req, res) => {
  try {
    const { email, otp } = req.body;
    const key = email.toLowerCase();
    const record = otpStore.get(key);

    if (!record)
      return res.status(400).json({ error: 'No OTP found. Please request a new one.' });

    if (Date.now() > record.expiresAt) {
      otpStore.delete(key);
      return res.status(400).json({ error: 'OTP expired. Please request a new one.' });
    }

    if (record.otp !== otp.toString().trim())
      return res.status(400).json({ error: 'Wrong OTP. Please try again.' });

    // OTP correct → create user
    const hashed = await bcrypt.hash(record.password, 10);
    await new User({
      name:       record.name,
      email:      key,
      password:   hashed,
      isVerified: true,
    }).save();

    otpStore.delete(key); // clean up

    res.json({ message: 'Gmail verified! Account created successfully.' });

  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// POST /login
app.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!isGmail(email))
      return res.status(400).json({ error: 'Only Gmail addresses allowed' });

    const user = await User.findOne({ email: email.toLowerCase() });
    if (!user)
      return res.status(401).json({ error: 'User not found' });

    if (!await bcrypt.compare(password, user.password))
      return res.status(401).json({ error: 'Wrong password' });

    const token = jwt.sign(
      { id: user._id, name: user.name, role: 'student' },
      JWT_SECRET, { expiresIn: '30d' }
    );
    res.json({ token, name: user.name, role: 'student' });

  } catch (e) { res.status(500).json({ error: e.message }); }
});

// POST /driver/login
app.post('/driver/login', (req, res) => {
  if (req.body.password !== DRIVER_PASS)
    return res.status(401).json({ error: 'Wrong driver password' });
  res.json({ token: jwt.sign({ role: 'driver' }, JWT_SECRET, { expiresIn: '12h' }) });
});

// ── SCHEDULES ─────────────────────────────────────────────────────
app.get('/buses', async (req, res) => {
  try {
    if (!req.query.day) return res.status(400).json({ error: 'day param required' });
    res.json(await Schedule.find({ days: req.query.day }));
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── LOCATION ──────────────────────────────────────────────────────
app.post('/updateLocation', verifyToken, async (req, res) => {
  try {
    const { busId, lat, lng } = req.body;
    if (!busId || lat == null || lng == null)
      return res.status(400).json({ error: 'busId, lat, lng required' });
    const existing = await BusLocation.findOne({ busId });
    if (existing && isExpired(existing)) {
      await BusLocation.findOneAndUpdate({ busId }, { started: false, route: [], sharingStartedAt: null });
      return res.status(403).json({ error: 'Sharing time limit exceeded.' });
    }
    let route = existing ? (existing.route || []) : [];
    route.push({ lat, lng });
    if (route.length > 200) route = route.slice(route.length - 200);
    const sharingStartedAt = (existing && existing.sharingStartedAt) ? existing.sharingStartedAt : new Date();
    await BusLocation.findOneAndUpdate(
      { busId },
      { lat, lng, started: true, updatedAt: new Date(), route, sharingStartedAt },
      { upsert: true }
    );
    res.json({ success: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post('/stopSharing', verifyToken, async (req, res) => {
  try {
    await BusLocation.findOneAndUpdate(
      { busId: req.body.busId },
      { started: false, route: [], sharingStartedAt: null }
    );
    res.json({ success: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get('/getLocation', async (req, res) => {
  try {
    const loc = await BusLocation.findOne({ busId: req.query.busId });
    if (!loc || !loc.started) return res.json({ started: false });
    if ((Date.now() - new Date(loc.updatedAt).getTime()) / 1000 > 30)
      return res.json({ started: false });
    if (isExpired(loc)) {
      await BusLocation.findOneAndUpdate(
        { busId: req.query.busId },
        { started: false, route: [], sharingStartedAt: null }
      );
      return res.json({ started: false });
    }
    res.json({ started: true, lat: loc.lat, lng: loc.lng, route: loc.route || [] });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post('/getETA', (req, res) => {
  const { busLat, busLng, stopLat, stopLng } = req.body;
  const R = 6371000, toRad = d => d * Math.PI / 180;
  const dLat = toRad(stopLat - busLat), dLng = toRad(stopLng - busLng);
  const a = Math.sin(dLat/2)**2 +
            Math.cos(toRad(busLat)) * Math.cos(toRad(stopLat)) * Math.sin(dLng/2)**2;
  const dist = R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
  res.json({ distanceMeters: Math.round(dist), etaMinutes: Math.ceil(dist / 8.33 / 60) });
});

app.get('/health', (_, res) => res.json({ status: 'ok' }));

// Auto-expire every 10 min
setInterval(async () => {
  try {
    const r = await BusLocation.updateMany(
      { started: true, sharingStartedAt: { $lt: new Date(Date.now() - SHARING_TIMEOUT_MS) } },
      { $set: { started: false, route: [], sharingStartedAt: null } }
    );
    if (r.modifiedCount > 0) console.log(`⏰ Auto-expired ${r.modifiedCount} bus(es)`);
  } catch (e) {}
}, 10 * 60 * 1000);

// Clean expired OTPs every 15 min
setInterval(() => {
  const now = Date.now();
  for (const [key, val] of otpStore.entries()) {
    if (now > val.expiresAt) otpStore.delete(key);
  }
}, 15 * 60 * 1000);

// ── SEED ──────────────────────────────────────────────────────────
async function seedSchedules() {
  const count = await Schedule.countDocuments();
  if (count > 0) { console.log(`📅 ${count} schedules in DB`); return; }

  const MAIN_CAMPUS   = { lat: 30.9687, lng: 76.4737 };
  const NEW_BUS_STAND = { lat: 30.9660, lng: 76.5170 };
  const OLD_BUS_STAND = { lat: 30.9641, lng: 76.5248 };
  const POLICE_LINES  = { lat: 30.9698, lng: 76.5052 };
  const SURJIT_HOSP   = { lat: 30.9715, lng: 76.5068 };
  const GS_RESORT     = { lat: 30.9738, lng: 76.5090 };
  const BELA_CHOWK    = { lat: 30.9752, lng: 76.5102 };
  const OVERBRIDGE    = { lat: 30.9706, lng: 76.5058 };
  const weekdays = ['Monday','Tuesday','Wednesday','Thursday'];

  await Schedule.insertMany([
    { busId:'BUS001',title:'Local Round Trip',departure:'08:15 AM',days:weekdays,stops:[
      {name:'Main Campus',time:'08:15 AM',...MAIN_CAMPUS},{name:'New Bus Stand',time:'08:27 AM',...NEW_BUS_STAND},
      {name:'Old Bus Stand / Railway Station',time:'08:30 AM',...OLD_BUS_STAND},{name:'Police Lines',time:'08:37 AM',...POLICE_LINES},
      {name:'Surjit Hospital Light Point',time:'08:42 AM',...SURJIT_HOSP},{name:'Overbridge',time:'08:45 AM',...OVERBRIDGE},
      {name:'Arrival at Main Campus',time:'08:50 AM',...MAIN_CAMPUS}]},
    { busId:'BUS002',title:'Local Round Trip',departure:'09:05 AM',days:weekdays,stops:[
      {name:'Main Campus',time:'09:05 AM',...MAIN_CAMPUS},{name:'New Bus Stand',time:'09:17 AM',...NEW_BUS_STAND},
      {name:'Old Bus Stand / Railway Station',time:'09:20 AM',...OLD_BUS_STAND},{name:'Police Lines',time:'09:27 AM',...POLICE_LINES},
      {name:'Bela Chowk',time:'09:37 AM',...BELA_CHOWK},{name:'Surjit Hospital Light Point',time:'09:42 AM',...SURJIT_HOSP},
      {name:'Overbridge',time:'09:45 AM',...OVERBRIDGE},{name:'Arrival at Main Campus',time:'09:50 AM',...MAIN_CAMPUS}]},
    { busId:'BUS003',title:'Local Trip to Bela Chowk and back',departure:'03:10 PM',days:weekdays,stops:[
      {name:'Main Campus',time:'03:10 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'03:20 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'03:22 PM',...GS_RESORT},{name:'Bela Chowk',time:'03:25 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'04:30 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'04:33 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'04:35 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'04:43 PM',...MAIN_CAMPUS}]},
    { busId:'BUS004',title:'Local Trip to Bela Chowk and back',departure:'05:00 PM',days:weekdays,stops:[
      {name:'Main Campus',time:'05:00 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'05:10 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'05:12 PM',...GS_RESORT},{name:'Bela Chowk',time:'05:15 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'05:18 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'05:21 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'05:23 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'05:31 PM',...MAIN_CAMPUS}]},
    { busId:'BUS005',title:'Local Trip to Bus Stand and Police Lines',departure:'05:50 PM',days:weekdays,stops:[
      {name:'Main Campus',time:'05:50 PM',...MAIN_CAMPUS},{name:'Overbridge',time:'05:55 PM',...OVERBRIDGE},
      {name:'New Bus Stand',time:'06:02 PM',...NEW_BUS_STAND},{name:'Old Bus Stand / Railway Station',time:'06:05 PM',...OLD_BUS_STAND},
      {name:'Police Lines',time:'06:12 PM',...POLICE_LINES},{name:'Surjit Hospital Light Point',time:'06:17 PM',...SURJIT_HOSP},
      {name:'Arrival at Main Campus',time:'06:25 PM',...MAIN_CAMPUS}]},
    { busId:'BUS006',title:'Local Round Trip',departure:'07:00 PM',days:weekdays,stops:[
      {name:'Main Campus',time:'07:00 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'07:10 PM',...SURJIT_HOSP},
      {name:'Police Lines',time:'07:20 PM',...POLICE_LINES},{name:'Bela Chowk',time:'07:30 PM',...BELA_CHOWK},
      {name:'G S Resort (Near HMT)',time:'07:33 PM',...GS_RESORT},{name:'Surjit Hospital Light Point (Return)',time:'07:35 PM',...SURJIT_HOSP},
      {name:'Arrival at Main Campus',time:'07:43 PM',...MAIN_CAMPUS}]},
    { busId:'BUS007',title:'Local Trip to Bus Stand and Police Lines',departure:'08:15 AM',days:['Friday'],stops:[
      {name:'Main Campus',time:'08:15 AM',...MAIN_CAMPUS},{name:'New Bus Stand',time:'08:27 AM',...NEW_BUS_STAND},
      {name:'Old Bus Stand / Railway Station',time:'08:30 AM',...OLD_BUS_STAND},{name:'Police Lines',time:'08:37 AM',...POLICE_LINES},
      {name:'Surjit Hospital Light Point',time:'08:42 AM',...SURJIT_HOSP},{name:'Overbridge',time:'08:45 AM',...OVERBRIDGE},
      {name:'Arrival at Main Campus',time:'08:50 AM',...MAIN_CAMPUS}]},
    { busId:'BUS008',title:'Local Trip to Bela Chowk and back',departure:'09:05 AM',days:['Friday'],stops:[
      {name:'Main Campus',time:'09:05 AM',...MAIN_CAMPUS},{name:'New Bus Stand',time:'09:17 AM',...NEW_BUS_STAND},
      {name:'Old Bus Stand / Railway Station',time:'09:20 AM',...OLD_BUS_STAND},{name:'Police Lines',time:'09:27 AM',...POLICE_LINES},
      {name:'Bela Chowk',time:'09:37 AM',...BELA_CHOWK},{name:'Surjit Hospital Light Point',time:'09:42 AM',...SURJIT_HOSP},
      {name:'Overbridge',time:'09:45 AM',...OVERBRIDGE},{name:'Arrival at Main Campus',time:'09:50 AM',...MAIN_CAMPUS}]},
    { busId:'BUS009',title:'Local Trip to Bela Chowk and back',departure:'03:10 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'03:10 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'03:20 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'03:22 PM',...GS_RESORT},{name:'Bela Chowk',time:'03:25 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'04:30 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'04:33 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'04:35 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'04:43 PM',...MAIN_CAMPUS}]},
    { busId:'BUS010',title:'Local Trip to Bela Chowk and back',departure:'05:00 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'05:00 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'05:10 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'05:12 PM',...GS_RESORT},{name:'Bela Chowk',time:'05:15 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'05:18 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'05:21 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'05:23 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'05:31 PM',...MAIN_CAMPUS}]},
    { busId:'BUS011',title:'Local Round Trip',departure:'05:50 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'05:50 PM',...MAIN_CAMPUS},{name:'Overbridge',time:'05:55 PM',...OVERBRIDGE},
      {name:'New Bus Stand',time:'06:02 PM',...NEW_BUS_STAND},{name:'Old Bus Stand / Railway Station',time:'06:05 PM',...OLD_BUS_STAND},
      {name:'Police Lines',time:'06:12 PM',...POLICE_LINES},{name:'Surjit Hospital Light Point',time:'06:17 PM',...SURJIT_HOSP},
      {name:'Arrival at Main Campus',time:'06:25 PM',...MAIN_CAMPUS}]},
    { busId:'BUS012',title:'Local Trip to Bela Chowk and back',departure:'06:15 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'06:15 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'06:25 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'06:27 PM',...GS_RESORT},{name:'Bela Chowk',time:'06:29 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'07:15 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'07:18 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'07:21 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'07:28 PM',...MAIN_CAMPUS}]},
    { busId:'BUS013',title:'Local Round Trip',departure:'07:10 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'07:10 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'07:20 PM',...SURJIT_HOSP},
      {name:'Police Lines',time:'07:30 PM',...POLICE_LINES},{name:'Bela Chowk',time:'07:40 PM',...BELA_CHOWK},
      {name:'Opp. G S Resort (Near HMT)',time:'07:43 PM',...GS_RESORT},{name:'Surjit Hospital Light Point (Return)',time:'07:45 PM',...SURJIT_HOSP},
      {name:'Arrival at Main Campus',time:'07:53 PM',...MAIN_CAMPUS}]},
    { busId:'BUS014',title:'Local Round Trip',departure:'08:30 AM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'08:30 AM',...MAIN_CAMPUS},{name:'New Bus Stand',time:'08:42 AM',...NEW_BUS_STAND},
      {name:'Old Bus Stand / Railway Station',time:'08:45 AM',...OLD_BUS_STAND},{name:'Police Lines',time:'08:52 AM',...POLICE_LINES},
      {name:'Surjit Hospital Light Point',time:'08:57 AM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'09:05 AM',...MAIN_CAMPUS}]},
    { busId:'BUS015',title:'Local Trip to Bela Chowk and back',departure:'10:30 AM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'10:30 AM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'10:40 AM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'10:42 AM',...GS_RESORT},{name:'Bela Chowk',time:'10:45 AM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'12:40 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'12:43 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'12:45 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'12:53 PM',...MAIN_CAMPUS}]},
    { busId:'BUS016',title:'Local Trip to Bela Chowk and back',departure:'11:00 AM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'11:00 AM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'11:10 AM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'11:12 AM',...GS_RESORT},{name:'Bela Chowk',time:'11:15 AM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'01:15 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'01:18 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'01:20 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'01:28 PM',...MAIN_CAMPUS}]},
    { busId:'BUS017',title:'Local Trip to Bela Chowk and back',departure:'03:00 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'03:00 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'03:10 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'03:12 PM',...GS_RESORT},{name:'Bela Chowk',time:'03:15 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'06:00 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'06:03 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'06:05 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'06:12 PM',...MAIN_CAMPUS}]},
    { busId:'BUS018',title:'Local Trip to Bela Chowk and back',departure:'04:00 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'04:00 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'04:10 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'04:12 PM',...GS_RESORT},{name:'Bela Chowk',time:'04:15 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'04:17 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'04:20 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'04:22 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'04:30 PM',...MAIN_CAMPUS}]},
    { busId:'BUS019',title:'Local Round Trip',departure:'05:30 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'05:30 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'05:40 PM',...SURJIT_HOSP},
      {name:'Opp. G S Resort (Near HMT)',time:'05:42 PM',...GS_RESORT},{name:'Bela Chowk',time:'05:45 PM',...BELA_CHOWK},
      {name:'Opp. G S Resort (Return)',time:'05:48 PM',...GS_RESORT},{name:'Police Lines',time:'05:55 PM',...POLICE_LINES},
      {name:'Surjit Hospital Light Point (Return)',time:'06:00 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'06:08 PM',...MAIN_CAMPUS}]},
    { busId:'BUS020',title:'Local Round Trip to Police Lines',departure:'06:40 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'06:40 PM',...MAIN_CAMPUS},{name:'New Bus Stand',time:'06:52 PM',...NEW_BUS_STAND},
      {name:'Old Bus Stand / Railway Station',time:'06:55 PM',...OLD_BUS_STAND},{name:'Police Lines',time:'07:02 PM',...POLICE_LINES},
      {name:'Surjit Hospital Light Point',time:'07:07 PM',...SURJIT_HOSP},{name:'Bela Chowk',time:'07:12 PM',...BELA_CHOWK},
      {name:'Opp. G S Resort (Near HMT)',time:'07:15 PM',...GS_RESORT},{name:'Surjit Hospital Light Point (2)',time:'07:17 PM',...SURJIT_HOSP},
      {name:'Arrival at Main Campus',time:'07:25 PM',...MAIN_CAMPUS}]},
    { busId:'BUS021',title:'Local Trip to Bela Chowk and back',departure:'10:00 AM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'10:00 AM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'10:10 AM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'10:12 AM',...GS_RESORT},{name:'Bela Chowk',time:'10:15 AM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'12:40 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'12:43 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'12:45 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'12:53 PM',...MAIN_CAMPUS}]},
    { busId:'BUS022',title:'Local Trip to Bela Chowk and back',departure:'11:00 AM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'11:00 AM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'11:10 AM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'11:12 AM',...GS_RESORT},{name:'Bela Chowk',time:'11:15 AM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'01:15 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'01:18 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'01:20 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'01:30 PM',...MAIN_CAMPUS}]},
    { busId:'BUS023',title:'Local Trip to Bela Chowk and back',departure:'03:30 PM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'03:30 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'03:40 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'03:42 PM',...GS_RESORT},{name:'Bela Chowk',time:'03:45 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'06:45 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'06:48 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'06:51 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'07:00 PM',...MAIN_CAMPUS}]},
    { busId:'BUS024',title:'Local Trip to Bela Chowk and back',departure:'04:15 PM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'04:15 PM',...MAIN_CAMPUS},{name:'Surjit Hospital Light Point',time:'04:25 PM',...SURJIT_HOSP},
      {name:'G S Resort (Near HMT)',time:'04:28 PM',...GS_RESORT},{name:'Bela Chowk',time:'04:31 PM',...BELA_CHOWK},
      {name:'Pick-up from Bela Chowk',time:'07:30 PM',...BELA_CHOWK},{name:'Opp. G S Resort (Near HMT)',time:'07:33 PM',...GS_RESORT},
      {name:'Surjit Hospital Light Point (Return)',time:'07:35 PM',...SURJIT_HOSP},{name:'Arrival at Main Campus',time:'07:45 PM',...MAIN_CAMPUS}]},
  ]);

  console.log('✅ All 24 buses seeded with exact IIT Ropar official schedule');
}

mongoose.connection.once('open', seedSchedules);

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server → http://0.0.0.0:${PORT}`);
  console.log(`📧 OTP emails sent via: ${MAIL_USER}`);
});