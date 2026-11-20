
 
/* =================================
   1. Test Java Backend Connection
================================= */

function testJavaBackend() {
    fetch("/api/parking")
        .then(response => {
            if (!response.ok) {
                throw new Error("Backend connection failed");
            }
            return response.json();
        })
        .then(data => {
            alert(data.message);
        })
        .catch(error => {
            alert("Java backend connection failed!");
            console.error("Backend error:", error);
        });
}


/* =================================
   2. Load Parking Availability
================================= */

function loadParkingSlots() {
    fetch("/api/parking")
        .then(response => {
            if (!response.ok) {
                throw new Error("Unable to get parking data");
            }
            return response.json();
        })
        .then(data => {

            // Update slot counts
            document.getElementById("totalSlots").textContent =
                data.totalSlots;

            document.getElementById("availableSlots").textContent =
                data.availableSlots;

            document.getElementById("occupiedSlots").textContent =
                data.occupiedSlots;

            // Display parking slots
            const container =
                document.getElementById("parkingSlots");

            container.innerHTML = "";

            data.slots.forEach(slot => {
                const card = document.createElement("div");

                card.className =
                    "slot " +
                    (slot.status === "Available"
                        ? "available"
                        : "occupied");

                const title = document.createElement("h3");
                title.textContent = slot.slot;

                const status = document.createElement("p");
                status.textContent = slot.status;

                card.appendChild(title);
                card.appendChild(status);

                container.appendChild(card);
            });

        })
        .catch(error => {
            console.error("Parking API error:", error);

            const container =
                document.getElementById("parkingSlots");

            if (container) {
                container.textContent =
                    "Unable to load parking slots. Please check the Java server.";
            }
        });
}


/* =================================
   3. Automatically Load Parking Data
================================= */

document.addEventListener("DOMContentLoaded", function () {

    // Run only on the Parking page
    if (document.getElementById("parkingSlots")) {
        loadParkingSlots();
    }

});

/* Reservation Form - Connect to Java Backend */

const reservationForm = document.getElementById("reservationForm");

if (reservationForm) {
    reservationForm.addEventListener("submit", function (event) {
        event.preventDefault();

        const customerName =
            document.getElementById("customerName").value.trim();

        const vehicleNumber =
            document.getElementById("vehicleNumber").value.trim();

        const parkingSlot =
            document.getElementById("parkingSlot").value;

        const message =
            document.getElementById("reservationMessage");

        if (!customerName || !vehicleNumber || !parkingSlot) {
            message.textContent = "Please fill in all details.";
            return;
        }

        const formData = new URLSearchParams();
        formData.append("customerName", customerName);
        formData.append("vehicleNumber", vehicleNumber);
        formData.append("parkingSlot", parkingSlot);

        fetch("http://localhost:8081/api/reserve", 
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: formData.toString()
        })
        .then(response => response.json())
        .then(data => {
            message.textContent = data.message;

            if (data.message.startsWith("Reservation successful")) {
                reservationForm.reset();
            }
        })
        .catch(error => {
            console.error("Reservation error:", error);
            message.textContent =
                "Could not connect to Java server. Please check if it is running.";
        });
    });
}

const queueForm = document.getElementById("queueForm");

if (queueForm) {
    queueForm.addEventListener("submit", async function (event) {
        event.preventDefault();

        const customerName = document.getElementById("queueName").value.trim();
        const vehicleNumber = document.getElementById("queueVehicle").value.trim();
        const queueMessage = document.getElementById("queueMessage");

        queueMessage.textContent = "Generating token...";

        try {
            const response = await fetch("http://localhost:8081/api/queue", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: new URLSearchParams({
                    customerName: customerName,
                    vehicleNumber: vehicleNumber
                })
            });

            const result = await response.json();

            if (response.ok) {
                queueMessage.textContent =
                    "Your token: " + result.tokenNumber +
                    " | Status: " + result.status;
            } else {
                queueMessage.textContent =
                    result.message || "Unable to generate token.";
            }
        } catch (error) {
            queueMessage.textContent =
                "Unable to connect to the server. Please try again.";
            console.error(error);
        }
    });
}

const checkQueueForm = document.getElementById("checkQueueForm");

if (checkQueueForm) {
    checkQueueForm.addEventListener("submit", async function (event) {
        event.preventDefault();

        const tokenNumber = document.getElementById("checkToken").value.trim();
        const message = document.getElementById("checkQueueMessage");

        message.textContent = "Checking status...";

        try {
            const response = await fetch(
                "http://localhost:8081/api/queue-status?token=" +
                encodeURIComponent(tokenNumber)
            );

            const result = await response.json();

            if (response.ok) {
                message.textContent =
                    "Token: " + result.tokenNumber +
                    " | Status: " + result.status;
            } else {
                message.textContent =
                    result.message || "Token not found.";
            }
        } catch (error) {
            message.textContent = "Unable to connect to server.";
            console.error(error);
        }
    });
}


async function updateSlot(status) {
    const slot = document.getElementById("adminSlot").value;
    const message = document.getElementById("adminMessage");

    message.textContent = "Updating parking slot...";

    try {
        const response = await fetch(
            "http://localhost:8081/api/admin/slot",
            {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: new URLSearchParams({
                    slotNumber: slot,
                    status: status
                })
            }
        );

        const result = await response.json();
        message.textContent = result.message;

    } catch (error) {
        message.textContent = "Unable to connect to Java server.";
        console.error(error);
    }
}
function callNextVehicle() {
    const message = document.getElementById("adminMessage");

    message.textContent =
        "Next vehicle feature will be connected to MySQL in the next step.";
}

async function loadAdminCounts() {
    const message = document.getElementById("adminMessage");

    try {
        const response = await fetch(
            "http://localhost:8081/api/admin/slots"
        );

        if (!response.ok) {
            throw new Error("Unable to load slot counts");
        }

        const data = await response.json();

        document.getElementById("totalSlots").textContent =
            data.totalSlots;

        document.getElementById("availableSlots").textContent =
            data.availableSlots;

        document.getElementById("occupiedSlots").textContent =
            data.occupiedSlots;

    } catch (error) {
        if (message) {
            message.textContent = "Unable to load parking counts.";
        }
        console.error(error);
    }
}

if (document.getElementById("totalSlots")) {
    loadAdminCounts();
}

async function callNextVehicle() {
    const message = document.getElementById("adminMessage");

    message.textContent = "Calling next vehicle...";

    try {
        const response = await fetch(
            "http://localhost:8081/api/admin/call-next",
            {
                method: "POST"
            }
        );

        const result = await response.json();
        message.textContent = result.tokenNumber
            ? result.message + " Token: " + result.tokenNumber
            : result.message;

    } catch (error) {
        message.textContent = "Unable to connect to Java server.";
        console.error(error);
    }
}

async function completeBooking() {
    const bookingId = document.getElementById("bookingId").value;
    const message = document.getElementById("bookingMessage");

    if (!bookingId) {
        message.textContent = "Please enter Booking ID.";
        return;
    }

    message.textContent = "Completing booking...";

    try {
        const response = await fetch(
            "http://localhost:8081/api/admin/complete-booking",
            {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: new URLSearchParams({ bookingId: bookingId })
            }
        );

        const result = await response.json();
        message.textContent = result.message;

        if (response.ok) {
            if (typeof loadAdminCounts === "function") {
                loadAdminCounts();
            }
        }
    } catch (error) {
        message.textContent = "Unable to connect to Java server.";
        console.error(error);
    }
}