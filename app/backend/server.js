// ================================================================
//  🚌 Smart Bus Tracking System — Production Backend
//  Features:
//    ✅ JWT Auth (bcrypt passwords)
//    ✅ Socket.IO real-time location push
//    ✅ FCM Push Notifications (bus arriving alert)
//    ✅ Live route history per bus
//    ✅ Multi-bus tracking
//    ✅ ETA calculation (Haversine)
//    ✅ Cloud-ready (Render / Railway)
// ================================================================

'use strict';

const express       = require('express');
const http          = require('http');
const { Server }    = require('socket.io');
const mongoose      = require('mongoose');
const bcrypt        = require('bcryptjs');
const jwt           = require('jsonwebtoken');
const cors          = require('cors');
const admin         = require('firebase-admin');

// ── ENV CONFIG ────────────────────────────────────────────────────
const JWT_SECRET    = process.env.JWT_SECRET    || 'CHANGE_ME_IN_PRODUCTION';
const MONGO_URI     = process.env.MONGO_URI     || 'mongodb://localhost:27017/bustrack';
const DRIVER_PASS   = process.env.DRIVER_PASS   || 'driver123';
const PORT          = process.env.PORT          || 3000;
const FIREBASE_KEY  = process.env.FIREBASE_KEY  || null;   // base64-encoded service account JSON

// ── FIREBASE ADMIN INIT ───────────────────────────────────────────
// Set FIREBASE_KEY env var on Render/Railway:
//   base64 encode your serviceAccountKey.json and paste as env var
if (FIREBASE_KEY) {
  try {
    const serviceAccount = JSON.parse(
      Buffer.from(FIREBASE_KEY, 'base64').toString('utf8')
    );
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
    });
    console.log('✅ Firebase Admin initialized');
  } catch (e) {
    console.warn('⚠️  Firebase Admin init failed:', e.message);
  }
} else {
  console.warn('⚠️  FIREBASE_KEY not set — push notifications disabled');
}

// ── EXPRESS + SOCKET.IO ───────────────────────────────────────────
const app    = express();
const server = http.createServer(app);
const io     = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'] }
});

app.use(cors());
app.use(express.json());

// ── MONGODB ───────────────────────────────────────────────────────
mongoose.connect(MONGO_URI)
  .then(() => console.log('✅ MongoDB connected'))
  .catch(err => console.error('❌ MongoDB error:', err));

// ── MODELS ────────────────────────────────────────────────────────

// Student users
const userSchema = new mongoose.Schema({
  name:      { type: String, required: true },
  email:     { type: String, required: true, unique: true, lowercase: true },
  password:  { type: String, required: true },
  fcmToken:  { type: String, default: '' },   // FCM device token
});
const User = mongoose.model('User', userSchema);

// Live bus state (one doc per busId)
const busLocationSchema = new mongoose.Schema({
  busId:     { type: String, required: true, unique: true },
  lat:       Number,
  lng:       Number,
  started:   { type: Boolean, default: false },
  updatedAt: { type: Date, default: Date.now },
  // Route history — array of { lat, lng } points for Polyline
  route:     [{ lat: Number, lng: Number }],
});
const BusLocation = mongoose.model('BusLocation', busLocationSchema);

// Bus schedules
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

// ── JWT MIDDLEWARE ────────────────────────────────────────────────
function verifyToken(req, res, next) {
  const auth  = req.headers['authorization'] || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'No token' });
  try {
    req.user = jwt.verify(token, JWT_SECRET);
    next();
  } catch {
    res.status(401).json({ error: 'Invalid or expired token' });
  }
}

// ── AUTH ROUTES ───────────────────────────────────────────────────

// POST /signup
app.post('/signup', async (req, res) => {
  try {
    const { name, email, password } = req.body;
    if (!name || !email || !password)
      return res.status(400).json({ error: 'Fill all fields' });

    if (await User.findOne({ email }))
      return res.status(409).json({ error: 'Email already registered' });

    const hashed = await bcrypt.hash(password, 10);
    await new User({ name, email, password: hashed }).save();
    res.json({ message: 'Signup successful' });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// POST /login
app.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;
    const user = await User.findOne({ email });
    if (!user) return res.status(401).json({ error: 'User not found' });

    const ok = await bcrypt.compare(password, user.password);
    if (!ok) return res.status(401).json({ error: 'Wrong password' });

    const token = jwt.sign(
      { id: user._id, name: user.name, role: 'student' },
      JWT_SECRET, { expiresIn: '30d' }
    );
    res.json({ token, name: user.name, role: 'student' });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// POST /driver/login
app.post('/driver/login', (req, res) => {
  if (req.body.password !== DRIVER_PASS)
    return res.status(401).json({ error: 'Wrong driver password' });

  const token = jwt.sign({ role: 'driver' }, JWT_SECRET, { expiresIn: '12h' });
  res.json({ token });
});

// POST /updateFcmToken  — called after student logs in
app.post('/updateFcmToken', verifyToken, async (req, res) => {
  try {
    const { fcmToken } = req.body;
    await User.findByIdAndUpdate(req.user.id, { fcmToken });
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── SCHEDULE ROUTES ───────────────────────────────────────────────

// GET /buses?day=Monday
app.get('/buses', async (req, res) => {
  try {
    const buses = await Schedule.find({ days: req.query.day });
    res.json(buses);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── LIVE LOCATION ROUTES ──────────────────────────────────────────

// POST /updateLocation  — driver sends location every 3s
// Body: { busId, lat, lng }
app.post('/updateLocation', verifyToken, async (req, res) => {
  try {
    const { busId, lat, lng } = req.body;
    if (!busId || lat == null || lng == null)
      return res.status(400).json({ error: 'busId, lat, lng required' });

    // Append to route history (keep last 200 points)
    const busLoc = await BusLocation.findOne({ busId });
    let route = busLoc ? busLoc.route || [] : [];
    route.push({ lat, lng });
    if (route.length > 200) route = route.slice(route.length - 200);

    await BusLocation.findOneAndUpdate(
      { busId },
      { lat, lng, started: true, updatedAt: new Date(), route },
      { upsert: true, new: true }
    );

    // 🔥 Emit real-time update to all students watching this bus
    io.to(`bus_${busId}`).emit('locationUpdate', { busId, lat, lng });

    // 🔔 Check ETA and send push notification if bus is close to any stop
    checkAndNotify(busId, lat, lng);

    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// POST /stopSharing
app.post('/stopSharing', verifyToken, async (req, res) => {
  try {
    const { busId } = req.body;
    await BusLocation.findOneAndUpdate(
      { busId },
      { started: false, route: [] }   // clear route on stop
    );
    io.to(`bus_${busId}`).emit('busStopped', { busId });
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// GET /getLocation?busId=BUS001
app.get('/getLocation', async (req, res) => {
  try {
    const loc = await BusLocation.findOne({ busId: req.query.busId });
    if (!loc || !loc.started) return res.json({ started: false });

    const ageSeconds = (Date.now() - new Date(loc.updatedAt).getTime()) / 1000;
    if (ageSeconds > 30) return res.json({ started: false });

    res.json({
      started: true,
      lat:     loc.lat,
      lng:     loc.lng,
      route:   loc.route || [],    // full route for Polyline
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── ETA ───────────────────────────────────────────────────────────

// POST /getETA  — Body: { busLat, busLng, stopLat, stopLng }
app.post('/getETA', (req, res) => {
  const { busLat, busLng, stopLat, stopLng } = req.body;
  const dist = haversine(busLat, busLng, stopLat, stopLng);
  const etaMinutes = Math.ceil(dist / 8.33 / 60);   // 30 km/h average
  res.json({ distanceMeters: Math.round(dist), etaMinutes });
});

// ── PUSH NOTIFICATION HELPER ──────────────────────────────────────

async function checkAndNotify(busId, busLat, busLng) {
  if (!admin.apps.length) return;   // Firebase not configured

  try {
    const schedule = await Schedule.findOne({ busId });
    if (!schedule) return;

    for (const stop of schedule.stops) {
      if (!stop.lat || !stop.lng) continue;

      const dist = haversine(busLat, busLng, stop.lat, stop.lng);

      // Notify when bus is within 500 metres of a stop
      if (dist < 500) {
        const etaMins = Math.ceil(dist / 8.33 / 60);
        const msg = etaMins <= 1
          ? `${schedule.title} is arriving at ${stop.name} now! 🚍`
          : `${schedule.title} arriving at ${stop.name} in ~${etaMins} min`;

        // Send to FCM topic — all students subscribed to this bus
        await admin.messaging().send({
          topic:        `bus_${busId}`,
          notification: {
            title: '🚌 Bus Alert',
            body:  msg,
          },
          android: {
            priority: 'high',
            notification: { sound: 'default' },
          },
        });

        break;   // only one notification per update
      }
    }
  } catch (e) {
    console.error('FCM error:', e.message);
  }
}

// ── HAVERSINE ─────────────────────────────────────────────────────
function haversine(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = d => d * Math.PI / 180;
  const dLat  = toRad(lat2 - lat1);
  const dLng  = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 +
            Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
            Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ── SOCKET.IO ─────────────────────────────────────────────────────
io.on('connection', (socket) => {
  console.log('🔌 Client connected:', socket.id);

  // Student joins a room for a specific bus
  socket.on('watchBus', (busId) => {
    socket.join(`bus_${busId}`);
    console.log(`👁️  ${socket.id} watching bus_${busId}`);
  });

  socket.on('stopWatching', (busId) => {
    socket.leave(`bus_${busId}`);
  });

  socket.on('disconnect', () => {
    console.log('🔌 Client disconnected:', socket.id);
  });
});

// ── HEALTH CHECK ──────────────────────────────────────────────────
app.get('/health', (_, res) => res.json({ status: 'ok', time: new Date() }));

// ── SEED DATA ─────────────────────────────────────────────────────
async function seedSchedules() {
  if (await Schedule.countDocuments() > 0) return;

  const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday'];

  // IIT Ropar gate coords — add real stop coordinates for accurate ETA notifications
  const GATE  = { lat: 30.9687, lng: 76.4737 };
  const BELA  = { lat: 30.9750, lng: 76.5100 };
  const RAIL  = { lat: 30.9641, lng: 76.5248 };

  const schedules = [
    // Mon–Thu
    { busId:'BUS001', title:'Local Round Trip',                        departure:'08:15 AM', days:weekdays,
      stops:[{ name:'IIT Ropar Gate', time:'08:15 AM', ...GATE },{ name:'Railway Station', time:'08:30 AM', ...RAIL }] },
    { busId:'BUS002', title:'Local Round Trip',                        departure:'09:05 AM', days:weekdays,
      stops:[{ name:'IIT Ropar Gate', time:'09:05 AM', ...GATE },{ name:'Bela Chowk',       time:'09:37 AM', ...BELA }] },
    { busId:'BUS003', title:'Local Trip to Bela Chowk and back',       departure:'03:10 PM', days:weekdays,
      stops:[{ name:'IIT Ropar Gate', time:'03:10 PM', ...GATE },{ name:'Bela Chowk',       time:'03:25 PM', ...BELA }] },
    { busId:'BUS004', title:'Local Trip to Bela Chowk and back',       departure:'05:00 PM', days:weekdays,
      stops:[{ name:'IIT Ropar Gate', time:'05:00 PM', ...GATE },{ name:'Bela Chowk',       time:'05:15 PM', ...BELA }] },
    { busId:'BUS005', title:'Local Trip to Bus Stand and Police Lines', departure:'05:50 PM', days:weekdays,
      stops:[{ name:'IIT Ropar Gate', time:'05:50 PM', ...GATE },{ name:'Railway Station',  time:'06:05 PM', ...RAIL }] },
    { busId:'BUS006', title:'Local Round Trip',                        departure:'07:00 PM', days:weekdays,
      stops:[{ name:'IIT Ropar Gate', time:'07:00 PM', ...GATE },{ name:'Bela Chowk',       time:'07:30 PM', ...BELA }] },
    // Friday
    { busId:'BUS007', title:'Local Trip to Bus Stand and Police Lines', departure:'08:15 AM', days:['Friday'],
      stops:[{ name:'IIT Ropar Gate', time:'08:15 AM', ...GATE },{ name:'Railway Station',  time:'08:30 AM', ...RAIL }] },
    { busId:'BUS008', title:'Local Trip to Bela Chowk and back',       departure:'09:05 AM', days:['Friday'],
      stops:[{ name:'IIT Ropar Gate', time:'09:05 AM', ...GATE },{ name:'Bela Chowk',       time:'09:37 AM', ...BELA }] },
    { busId:'BUS009', title:'Local Trip to Bela Chowk and back',       departure:'03:10 PM', days:['Friday'],
      stops:[{ name:'IIT Ropar Gate', time:'03:10 PM', ...GATE },{ name:'Bela Chowk',       time:'03:25 PM', ...BELA }] },
    { busId:'BUS010', title:'Local Trip to Bela Chowk and back',       departure:'05:00 PM', days:['Friday'],
      stops:[{ name:'IIT Ropar Gate', time:'05:00 PM', ...GATE },{ name:'Bela Chowk',       time:'05:15 PM', ...BELA }] },
    { busId:'BUS011', title:'Local Round Trip',                        departure:'05:50 PM', days:['Friday'],
      stops:[{ name:'IIT Ropar Gate', time:'05:50 PM', ...GATE },{ name:'Railway Station',  time:'06:05 PM', ...RAIL }] },
    { busId:'BUS012', title:'Local Trip to Bela Chowk and back',       departure:'06:15 PM', days:['Friday'],
      stops:[{ name:'IIT Ropar Gate', time:'06:15 PM', ...GATE },{ name:'Bela Chowk',       time:'06:29 PM', ...BELA }] },
    { busId:'BUS013', title:'Local Round Trip',                        departure:'07:10 PM', days:['Friday'],
      stops:[{ name:'IIT Ropar Gate', time:'07:10 PM', ...GATE },{ name:'Bela Chowk',       time:'07:40 PM', ...BELA }] },
    // Saturday
    { busId:'BUS014', title:'Local Round Trip',                        departure:'08:30 AM', days:['Saturday'],
      stops:[{ name:'IIT Ropar Gate', time:'08:30 AM', ...GATE },{ name:'Railway Station',  time:'08:45 AM', ...RAIL }] },
    { busId:'BUS015', title:'Local Trip to Bela Chowk and back',       departure:'10:30 AM', days:['Saturday'],
      stops:[{ name:'IIT Ropar Gate', time:'10:30 AM', ...GATE },{ name:'Bela Chowk',       time:'10:45 AM', ...BELA }] },
    { busId:'BUS016', title:'Local Trip to Bela Chowk and back',       departure:'11:00 AM', days:['Saturday'],
      stops:[{ name:'IIT Ropar Gate', time:'11:00 AM', ...GATE },{ name:'Bela Chowk',       time:'11:15 AM', ...BELA }] },
    { busId:'BUS017', title:'Local Trip to Bela Chowk and back',       departure:'03:00 PM', days:['Saturday'],
      stops:[{ name:'IIT Ropar Gate', time:'03:00 PM', ...GATE },{ name:'Bela Chowk',       time:'03:15 PM', ...BELA }] },
    { busId:'BUS018', title:'Local Trip to Bela Chowk and back',       departure:'04:00 PM', days:['Saturday'],
      stops:[{ name:'IIT Ropar Gate', time:'04:00 PM', ...GATE },{ name:'Bela Chowk',       time:'04:15 PM', ...BELA }] },
    { busId:'BUS019', title:'Local Round Trip',                        departure:'05:30 PM', days:['Saturday'],
      stops:[{ name:'IIT Ropar Gate', time:'05:30 PM', ...GATE },{ name:'Bela Chowk',       time:'05:45 PM', ...BELA }] },
    { busId:'BUS020', title:'Local Round Trip',                        departure:'06:40 PM', days:['Saturday'],
      stops:[{ name:'IIT Ropar Gate', time:'06:40 PM', ...GATE },{ name:'Railway Station',  time:'06:55 PM', ...RAIL }] },
    // Sunday
    { busId:'BUS021', title:'Local Trip to Bela Chowk and back',       departure:'10:00 AM', days:['Sunday'],
      stops:[{ name:'IIT Ropar Gate', time:'10:00 AM', ...GATE },{ name:'Bela Chowk',       time:'10:15 AM', ...BELA }] },
    { busId:'BUS022', title:'Local Trip to Bela Chowk and back',       departure:'11:00 AM', days:['Sunday'],
      stops:[{ name:'IIT Ropar Gate', time:'11:00 AM', ...GATE },{ name:'Bela Chowk',       time:'11:15 AM', ...BELA }] },
    { busId:'BUS023', title:'Local Trip to Bela Chowk and back',       departure:'03:30 PM', days:['Sunday'],
      stops:[{ name:'IIT Ropar Gate', time:'03:30 PM', ...GATE },{ name:'Bela Chowk',       time:'03:45 PM', ...BELA }] },
    { busId:'BUS024', title:'Local Trip to Bela Chowk and back',       departure:'04:15 PM', days:['Sunday'],
      stops:[{ name:'IIT Ropar Gate', time:'04:15 PM', ...GATE },{ name:'Bela Chowk',       time:'04:31 PM', ...BELA }] },
  ];

  await Schedule.insertMany(schedules);
  console.log('✅ Seed schedules inserted');
}

mongoose.connection.once('open', seedSchedules);

// ── START ─────────────────────────────────────────────────────────
server.listen(PORT, '0.0.0.0', () =>
  console.log(`🚀 Server running on http://0.0.0.0:${PORT}`)
);
