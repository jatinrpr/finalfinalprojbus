require('dotenv').config();

'use strict';

const express    = require('express');
const mongoose   = require('mongoose');
const bcrypt     = require('bcryptjs');
const jwt        = require('jsonwebtoken');
const cors       = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

const JWT_SECRET  = process.env.JWT_SECRET  || 'iitropar@BusTrack#2024$SecureKey!XyZ';
const MONGO_URI   = process.env.MONGO_URI   || 'mongodb://localhost:27017/bustrack';
const DRIVER_PASS = process.env.DRIVER_PASS || 'driver123';
const PORT        = process.env.PORT        || 3000;

const SHARING_TIMEOUT_MS = 2 * 60 * 60 * 1000;

// ── MONGODB ───────────────────────────────────────────────────────
mongoose.connect(MONGO_URI)
  .then(() => console.log('✅ MongoDB connected'))
  .catch(err => console.error('❌ MongoDB error:', err.message));

// ── MODELS ────────────────────────────────────────────────────────
const userSchema = new mongoose.Schema({
  name:     { type: String, required: true },
  email:    { type: String, required: true, unique: true, lowercase: true },
  password: { type: String, required: true },
});
const User = mongoose.model('User', userSchema);

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

const stopSchema    = new mongoose.Schema({ name: String, time: String, lat: Number, lng: Number });
const scheduleSchema = new mongoose.Schema({
  busId:     { type: String, required: true, unique: true },
  title:     String,
  departure: String,
  days:      [String],
  stops:     [stopSchema],
});
const Schedule = mongoose.model('Schedule', scheduleSchema);

// ── HELPERS ───────────────────────────────────────────────────────
const isGmail = e => typeof e === 'string' && e.toLowerCase().endsWith('@gmail.com');

function verifyToken(req, res, next) {
  const auth  = req.headers['authorization'] || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'No token' });
  try { req.user = jwt.verify(token, JWT_SECRET); next(); }
  catch { res.status(401).json({ error: 'Invalid or expired token' }); }
}

function isExpired(b) {
  if (!b || !b.started) return true;
  if (!b.sharingStartedAt) return false;
  return Date.now() - new Date(b.sharingStartedAt).getTime() > SHARING_TIMEOUT_MS;
}

// ── AUTH ──────────────────────────────────────────────────────────

// POST /signup  — simple, no OTP
app.post('/signup', async (req, res) => {
  try {
    const { name, email, password } = req.body;

    if (!name || !email || !password)
      return res.status(400).json({ error: 'Fill all fields' });

    if (!isGmail(email))
      return res.status(400).json({ error: 'Only Gmail addresses allowed' });

    if (password.length < 6)
      return res.status(400).json({ error: 'Password must be at least 6 characters' });

    if (await User.findOne({ email: email.toLowerCase() }))
      return res.status(409).json({ error: 'Email already registered' });

    const hashed = await bcrypt.hash(password, 10);
    await new User({ name, email: email.toLowerCase(), password: hashed }).save();

    res.json({ message: 'Signup successful' });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// POST /login
app.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!isGmail(email))
      return res.status(400).json({ error: 'Only Gmail addresses allowed' });

    const user = await User.findOne({ email: email.toLowerCase() });
    if (!user) return res.status(401).json({ error: 'User not found' });

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
      await BusLocation.findOneAndUpdate({ busId },
        { started: false, route: [], sharingStartedAt: null });
      return res.status(403).json({ error: 'Sharing time limit exceeded.' });
    }

    let route = existing ? (existing.route || []) : [];
    route.push({ lat, lng });
    if (route.length > 200) route = route.slice(route.length - 200);

    const sharingStartedAt = existing?.sharingStartedAt || new Date();
    await BusLocation.findOneAndUpdate({ busId },
      { lat, lng, started: true, updatedAt: new Date(), route, sharingStartedAt },
      { upsert: true });

    res.json({ success: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post('/stopSharing', verifyToken, async (req, res) => {
  try {
    await BusLocation.findOneAndUpdate({ busId: req.body.busId },
      { started: false, route: [], sharingStartedAt: null });
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
      await BusLocation.findOneAndUpdate({ busId: req.query.busId },
        { started: false, route: [], sharingStartedAt: null });
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
  res.json({ distanceMeters: Math.round(dist), etaMinutes: Math.ceil(dist/8.33/60) });
});

app.get('/health', (_, res) => res.json({ status: 'ok', time: new Date() }));

// Auto-expire every 10 min
setInterval(async () => {
  try {
    const r = await BusLocation.updateMany(
      { started: true, sharingStartedAt: { $lt: new Date(Date.now() - SHARING_TIMEOUT_MS) } },
      { $set: { started: false, route: [], sharingStartedAt: null } });
    if (r.modifiedCount > 0) console.log(`⏰ Auto-expired ${r.modifiedCount} bus(es)`);
  } catch {}
}, 10 * 60 * 1000);

// ── SEED ──────────────────────────────────────────────────────────
async function seedSchedules() {
  const count = await Schedule.countDocuments();
  if (count > 0) { console.log(`📅 ${count} schedules in DB`); return; }

  const MC={lat:30.9687,lng:76.4737}, NBS={lat:30.9660,lng:76.5170};
  const OBS={lat:30.9641,lng:76.5248}, PL={lat:30.9698,lng:76.5052};
  const SH={lat:30.9715,lng:76.5068}, GS={lat:30.9738,lng:76.5090};
  const BC={lat:30.9752,lng:76.5102}, OB={lat:30.9706,lng:76.5058};
  const wd=['Monday','Tuesday','Wednesday','Thursday'];

  await Schedule.insertMany([
    {busId:'BUS001',title:'Local Round Trip',departure:'08:15 AM',days:wd,stops:[
      {name:'Main Campus',time:'08:15 AM',...MC},{name:'New Bus Stand',time:'08:27 AM',...NBS},
      {name:'Old Bus Stand / Railway Station',time:'08:30 AM',...OBS},{name:'Police Lines',time:'08:37 AM',...PL},
      {name:'Surjit Hospital Light Point',time:'08:42 AM',...SH},{name:'Overbridge',time:'08:45 AM',...OB},
      {name:'Arrival at Main Campus',time:'08:50 AM',...MC}]},
    {busId:'BUS002',title:'Local Round Trip',departure:'09:05 AM',days:wd,stops:[
      {name:'Main Campus',time:'09:05 AM',...MC},{name:'New Bus Stand',time:'09:17 AM',...NBS},
      {name:'Old Bus Stand / Railway Station',time:'09:20 AM',...OBS},{name:'Police Lines',time:'09:27 AM',...PL},
      {name:'Bela Chowk',time:'09:37 AM',...BC},{name:'Surjit Hospital Light Point',time:'09:42 AM',...SH},
      {name:'Overbridge',time:'09:45 AM',...OB},{name:'Arrival at Main Campus',time:'09:50 AM',...MC}]},
    {busId:'BUS003',title:'Local Trip to Bela Chowk and back',departure:'03:10 PM',days:wd,stops:[
      {name:'Main Campus',time:'03:10 PM',...MC},{name:'Surjit Hospital Light Point',time:'03:20 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'03:22 PM',...GS},{name:'Bela Chowk',time:'03:25 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'04:30 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'04:33 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'04:35 PM',...SH},{name:'Arrival at Main Campus',time:'04:43 PM',...MC}]},
    {busId:'BUS004',title:'Local Trip to Bela Chowk and back',departure:'05:00 PM',days:wd,stops:[
      {name:'Main Campus',time:'05:00 PM',...MC},{name:'Surjit Hospital Light Point',time:'05:10 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'05:12 PM',...GS},{name:'Bela Chowk',time:'05:15 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'05:18 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'05:21 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'05:23 PM',...SH},{name:'Arrival at Main Campus',time:'05:31 PM',...MC}]},
    {busId:'BUS005',title:'Local Trip to Bus Stand and Police Lines',departure:'05:50 PM',days:wd,stops:[
      {name:'Main Campus',time:'05:50 PM',...MC},{name:'Overbridge',time:'05:55 PM',...OB},
      {name:'New Bus Stand',time:'06:02 PM',...NBS},{name:'Old Bus Stand / Railway Station',time:'06:05 PM',...OBS},
      {name:'Police Lines',time:'06:12 PM',...PL},{name:'Surjit Hospital Light Point',time:'06:17 PM',...SH},
      {name:'Arrival at Main Campus',time:'06:25 PM',...MC}]},
    {busId:'BUS006',title:'Local Round Trip',departure:'07:00 PM',days:wd,stops:[
      {name:'Main Campus',time:'07:00 PM',...MC},{name:'Surjit Hospital Light Point',time:'07:10 PM',...SH},
      {name:'Police Lines',time:'07:20 PM',...PL},{name:'Bela Chowk',time:'07:30 PM',...BC},
      {name:'G S Resort (Near HMT)',time:'07:33 PM',...GS},{name:'Surjit Hospital Light Point (Return)',time:'07:35 PM',...SH},
      {name:'Arrival at Main Campus',time:'07:43 PM',...MC}]},
    {busId:'BUS007',title:'Local Trip to Bus Stand and Police Lines',departure:'08:15 AM',days:['Friday'],stops:[
      {name:'Main Campus',time:'08:15 AM',...MC},{name:'New Bus Stand',time:'08:27 AM',...NBS},
      {name:'Old Bus Stand / Railway Station',time:'08:30 AM',...OBS},{name:'Police Lines',time:'08:37 AM',...PL},
      {name:'Surjit Hospital Light Point',time:'08:42 AM',...SH},{name:'Overbridge',time:'08:45 AM',...OB},
      {name:'Arrival at Main Campus',time:'08:50 AM',...MC}]},
    {busId:'BUS008',title:'Local Trip to Bela Chowk and back',departure:'09:05 AM',days:['Friday'],stops:[
      {name:'Main Campus',time:'09:05 AM',...MC},{name:'New Bus Stand',time:'09:17 AM',...NBS},
      {name:'Old Bus Stand / Railway Station',time:'09:20 AM',...OBS},{name:'Police Lines',time:'09:27 AM',...PL},
      {name:'Bela Chowk',time:'09:37 AM',...BC},{name:'Surjit Hospital Light Point',time:'09:42 AM',...SH},
      {name:'Overbridge',time:'09:45 AM',...OB},{name:'Arrival at Main Campus',time:'09:50 AM',...MC}]},
    {busId:'BUS009',title:'Local Trip to Bela Chowk and back',departure:'03:10 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'03:10 PM',...MC},{name:'Surjit Hospital Light Point',time:'03:20 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'03:22 PM',...GS},{name:'Bela Chowk',time:'03:25 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'04:30 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'04:33 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'04:35 PM',...SH},{name:'Arrival at Main Campus',time:'04:43 PM',...MC}]},
    {busId:'BUS010',title:'Local Trip to Bela Chowk and back',departure:'05:00 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'05:00 PM',...MC},{name:'Surjit Hospital Light Point',time:'05:10 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'05:12 PM',...GS},{name:'Bela Chowk',time:'05:15 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'05:18 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'05:21 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'05:23 PM',...SH},{name:'Arrival at Main Campus',time:'05:31 PM',...MC}]},
    {busId:'BUS011',title:'Local Round Trip',departure:'05:50 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'05:50 PM',...MC},{name:'Overbridge',time:'05:55 PM',...OB},
      {name:'New Bus Stand',time:'06:02 PM',...NBS},{name:'Old Bus Stand / Railway Station',time:'06:05 PM',...OBS},
      {name:'Police Lines',time:'06:12 PM',...PL},{name:'Surjit Hospital Light Point',time:'06:17 PM',...SH},
      {name:'Arrival at Main Campus',time:'06:25 PM',...MC}]},
    {busId:'BUS012',title:'Local Trip to Bela Chowk and back',departure:'06:15 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'06:15 PM',...MC},{name:'Surjit Hospital Light Point',time:'06:25 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'06:27 PM',...GS},{name:'Bela Chowk',time:'06:29 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'07:15 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'07:18 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'07:21 PM',...SH},{name:'Arrival at Main Campus',time:'07:28 PM',...MC}]},
    {busId:'BUS013',title:'Local Round Trip',departure:'07:10 PM',days:['Friday'],stops:[
      {name:'Main Campus',time:'07:10 PM',...MC},{name:'Surjit Hospital Light Point',time:'07:20 PM',...SH},
      {name:'Police Lines',time:'07:30 PM',...PL},{name:'Bela Chowk',time:'07:40 PM',...BC},
      {name:'Opp. G S Resort (Near HMT)',time:'07:43 PM',...GS},{name:'Surjit Hospital Light Point (Return)',time:'07:45 PM',...SH},
      {name:'Arrival at Main Campus',time:'07:53 PM',...MC}]},
    {busId:'BUS014',title:'Local Round Trip',departure:'08:30 AM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'08:30 AM',...MC},{name:'New Bus Stand',time:'08:42 AM',...NBS},
      {name:'Old Bus Stand / Railway Station',time:'08:45 AM',...OBS},{name:'Police Lines',time:'08:52 AM',...PL},
      {name:'Surjit Hospital Light Point',time:'08:57 AM',...SH},{name:'Arrival at Main Campus',time:'09:05 AM',...MC}]},
    {busId:'BUS015',title:'Local Trip to Bela Chowk and back',departure:'10:30 AM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'10:30 AM',...MC},{name:'Surjit Hospital Light Point',time:'10:40 AM',...SH},
      {name:'G S Resort (Near HMT)',time:'10:42 AM',...GS},{name:'Bela Chowk',time:'10:45 AM',...BC},
      {name:'Pick-up from Bela Chowk',time:'12:40 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'12:43 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'12:45 PM',...SH},{name:'Arrival at Main Campus',time:'12:53 PM',...MC}]},
    {busId:'BUS016',title:'Local Trip to Bela Chowk and back',departure:'11:00 AM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'11:00 AM',...MC},{name:'Surjit Hospital Light Point',time:'11:10 AM',...SH},
      {name:'G S Resort (Near HMT)',time:'11:12 AM',...GS},{name:'Bela Chowk',time:'11:15 AM',...BC},
      {name:'Pick-up from Bela Chowk',time:'01:15 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'01:18 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'01:20 PM',...SH},{name:'Arrival at Main Campus',time:'01:28 PM',...MC}]},
    {busId:'BUS017',title:'Local Trip to Bela Chowk and back',departure:'03:00 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'03:00 PM',...MC},{name:'Surjit Hospital Light Point',time:'03:10 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'03:12 PM',...GS},{name:'Bela Chowk',time:'03:15 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'06:00 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'06:03 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'06:05 PM',...SH},{name:'Arrival at Main Campus',time:'06:12 PM',...MC}]},
    {busId:'BUS018',title:'Local Trip to Bela Chowk and back',departure:'04:00 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'04:00 PM',...MC},{name:'Surjit Hospital Light Point',time:'04:10 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'04:12 PM',...GS},{name:'Bela Chowk',time:'04:15 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'04:17 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'04:20 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'04:22 PM',...SH},{name:'Arrival at Main Campus',time:'04:30 PM',...MC}]},
    {busId:'BUS019',title:'Local Round Trip',departure:'05:30 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'05:30 PM',...MC},{name:'Surjit Hospital Light Point',time:'05:40 PM',...SH},
      {name:'Opp. G S Resort (Near HMT)',time:'05:42 PM',...GS},{name:'Bela Chowk',time:'05:45 PM',...BC},
      {name:'Opp. G S Resort (Return)',time:'05:48 PM',...GS},{name:'Police Lines',time:'05:55 PM',...PL},
      {name:'Surjit Hospital Light Point (Return)',time:'06:00 PM',...SH},{name:'Arrival at Main Campus',time:'06:08 PM',...MC}]},
    {busId:'BUS020',title:'Local Round Trip to Police Lines',departure:'06:40 PM',days:['Saturday'],stops:[
      {name:'Main Campus',time:'06:40 PM',...MC},{name:'New Bus Stand',time:'06:52 PM',...NBS},
      {name:'Old Bus Stand / Railway Station',time:'06:55 PM',...OBS},{name:'Police Lines',time:'07:02 PM',...PL},
      {name:'Surjit Hospital Light Point',time:'07:07 PM',...SH},{name:'Bela Chowk',time:'07:12 PM',...BC},
      {name:'Opp. G S Resort (Near HMT)',time:'07:15 PM',...GS},{name:'Surjit Hospital Light Point (2)',time:'07:17 PM',...SH},
      {name:'Arrival at Main Campus',time:'07:25 PM',...MC}]},
    {busId:'BUS021',title:'Local Trip to Bela Chowk and back',departure:'10:00 AM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'10:00 AM',...MC},{name:'Surjit Hospital Light Point',time:'10:10 AM',...SH},
      {name:'G S Resort (Near HMT)',time:'10:12 AM',...GS},{name:'Bela Chowk',time:'10:15 AM',...BC},
      {name:'Pick-up from Bela Chowk',time:'12:40 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'12:43 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'12:45 PM',...SH},{name:'Arrival at Main Campus',time:'12:53 PM',...MC}]},
    {busId:'BUS022',title:'Local Trip to Bela Chowk and back',departure:'11:00 AM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'11:00 AM',...MC},{name:'Surjit Hospital Light Point',time:'11:10 AM',...SH},
      {name:'G S Resort (Near HMT)',time:'11:12 AM',...GS},{name:'Bela Chowk',time:'11:15 AM',...BC},
      {name:'Pick-up from Bela Chowk',time:'01:15 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'01:18 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'01:20 PM',...SH},{name:'Arrival at Main Campus',time:'01:30 PM',...MC}]},
    {busId:'BUS023',title:'Local Trip to Bela Chowk and back',departure:'03:30 PM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'03:30 PM',...MC},{name:'Surjit Hospital Light Point',time:'03:40 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'03:42 PM',...GS},{name:'Bela Chowk',time:'03:45 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'06:45 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'06:48 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'06:51 PM',...SH},{name:'Arrival at Main Campus',time:'07:00 PM',...MC}]},
    {busId:'BUS024',title:'Local Trip to Bela Chowk and back',departure:'04:15 PM',days:['Sunday'],stops:[
      {name:'Main Campus',time:'04:15 PM',...MC},{name:'Surjit Hospital Light Point',time:'04:25 PM',...SH},
      {name:'G S Resort (Near HMT)',time:'04:28 PM',...GS},{name:'Bela Chowk',time:'04:31 PM',...BC},
      {name:'Pick-up from Bela Chowk',time:'07:30 PM',...BC},{name:'Opp. G S Resort (Near HMT)',time:'07:33 PM',...GS},
      {name:'Surjit Hospital Light Point (Return)',time:'07:35 PM',...SH},{name:'Arrival at Main Campus',time:'07:45 PM',...MC}]},
  ]);
  console.log('✅ All 24 buses seeded!');
}

mongoose.connection.once('open', seedSchedules);

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server running on port ${PORT}`);
  console.log(`🔑 Driver password: ${DRIVER_PASS}`);
});