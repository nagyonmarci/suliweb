import pypff
import os
import time
from datetime import datetime
from loguru import logger


def format_timestamp(timestamp):
    """Convert Pypff timestamp to Unix timestamp."""
    if timestamp is None:
        return None
    try:
        # Pypff timestamps are in Windows FILETIME format (100-nanosecond intervals since 1601-01-01)
        # Convert to Unix timestamp (seconds since 1970-01-01)
        unix_timestamp = (timestamp - 116444736000000000) // 10000000
        return unix_timestamp
    except Exception as e:
        logger.warning(f"Timestamp formázási hiba: {timestamp} - {str(e)}")
        return None


def extract_email_data(email_item):
    """Extract email data from a Pypff email item."""
    try:
        # Extract basic email information
        message_id = email_item.message_id if email_item.message_id else None
        subject = email_item.subject if email_item.subject else None
        sender = email_item.sender_name if email_item.sender_name else None

        # Extract recipients
        recipients = []
        if email_item.recipients:
            for recipient in email_item.recipients:
                if recipient.email_address:
                    recipients.append(recipient.email_address)
                elif recipient.name:
                    recipients.append(recipient.name)

        recipients_str = ",".join(recipients) if recipients else None

        # Extract timestamps
        sent_at = format_timestamp(email_item.sending_time)
        received_at = format_timestamp(email_item.received_time)

        # Extract body
        body_text = ""
        if email_item.plain_text_body:
            body_text = email_item.plain_text_body
        elif email_item.html_body:
            body_text = email_item.html_body

        # Extract attachment count
        attachment_count = email_item.number_of_attachments if email_item.number_of_attachments else 0

        # Extract subject (if not available in email_item.subject)
        if not subject:
            subject = email_item.display_name if email_item.display_name else None

        return {
            'message_id': message_id,
            'subject': subject,
            'sender': sender,
            'recipients': recipients_str,
            'sent_at': sent_at,
            'received_at': received_at,
            'body_text': body_text,
            'attachment_count': attachment_count,
            'has_attachments': attachment_count > 0
        }
    except Exception as e:
        logger.error(f"Hiba az email adatok kinyerésekor: {str(e)}")
        return None


def read_pst_file(pst_path, db):
    """Read a PST file and extract email data."""
    logger.info(f"PST fájl olvasása: {pst_path}")

    email_count = 0
    attachment_count = 0

    try:
        # Open the PST file
        pst_file = pypff.open(pst_path)

        # Get the root folder
        root_folder = pst_file.get_root_folder()

        # Recursively process folders
        folders_to_process = [root_folder]

        while folders_to_process:
            folder = folders_to_process.pop()

            # Process messages in the folder
            if hasattr(folder, 'get_sub_folders'):
                for sub_folder in folder.get_sub_folders():
                    folders_to_process.append(sub_folder)

            # Process emails in the folder
            if hasattr(folder, 'get_messages'):
                for message in folder.get_messages():
                    if message:
                        # Check if it's an email message (IPM.Note)
                        message_class = message.message_class if message.message_class else ""
                        if message_class.startswith("IPM.Note") or not message_class:
                            try:
                                email_data = extract_email_data(message)
                                if email_data:
                                    # Save to database
                                    db.insert_email(pst_path, email_data)
                                    email_count += 1

                                    # Count attachments
                                    attachment_count += email_data['attachment_count']

                                    # Log progress
                                    if email_count % 100 == 0:
                                        logger.info(f"Email kinyerés: {email_count} email, {attachment_count} attachment")

                            except Exception as e:
                                logger.error(f"Hiba az email feldolgozása közben: {str(e)}")
                                continue

        # Close the PST file
        pst_file.close()

        logger.info(f"PST fájl beolvasva: {email_count} email, {attachment_count} attachment")
        return email_count, attachment_count

    except Exception as e:
        logger.error(f"Hiba a PST fájl beolvasása közben: {pst_path} - {str(e)}")
        raise e


def get_pst_info(pst_path):
    """Get basic information about a PST file."""
    try:
        pst_file = pypff.open(pst_path)
        info = {
            'name': os.path.basename(pst_path),
            'path': pst_path,
            'size': os.path.getsize(pst_path),
            'number_of_folders': pst_file.number_of_folders,
            'number_of_messages': pst_file.number_of_messages,
            'version': pst_file.version
        }
        pst_file.close()
        return info
    except Exception as e:
        logger.error(f"Hiba a PST fájl információk lekérdezése közben: {pst_path} - {str(e)}")
        return None